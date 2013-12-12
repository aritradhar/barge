/**
 * Copyright 2013 David Rusek <dave dot rusek at gmail dot com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.robotninjas.barge.log;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.inject.Inject;
import com.google.protobuf.ByteString;
import journal.io.api.Journal;
import org.robotninjas.barge.ClusterConfig;
import com.google.common.util.concurrent.SettableFuture;
import org.robotninjas.barge.Replica;
import org.robotninjas.barge.rpc.RaftExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.propagate;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.Collections.unmodifiableList;
import static org.robotninjas.barge.proto.RaftEntry.Entry;
import static org.robotninjas.barge.proto.RaftProto.AppendEntries;

@NotThreadSafe
public class RaftLog {

  private static final Logger LOGGER = LoggerFactory.getLogger(RaftLog.class);
  private static final Entry SENTINEL = Entry.newBuilder().setCommand(ByteString.EMPTY).setTerm(0).build();

  private final TreeMap<Long, Entry> log = Maps.newTreeMap();
  private final ClusterConfig config;
  private final StateMachineProxy stateMachine;
  private final RaftJournal journal;
  private final ListeningExecutorService executor;

  private volatile long lastLogIndex = 0;
  private volatile long lastLogTerm = 0;
  private volatile long currentTerm = 0;
  private volatile Optional<Replica> votedFor = Optional.absent();
  private volatile long commitIndex = 0;
  private volatile long lastApplied = 0;

  @Inject
  RaftLog(@Nonnull Journal journal, @Nonnull ClusterConfig config,
          @Nonnull StateMachineProxy stateMachine, @RaftExecutor ListeningExecutorService raftThread) {
    this.journal = new RaftJournal(checkNotNull(journal));
    this.config = checkNotNull(config);
    this.stateMachine = checkNotNull(stateMachine);
    this.executor = checkNotNull(raftThread);
  }

  public void load() {

    LOGGER.info("Replaying log");

    journal.init();

    journal.replay(new RaftJournal.Visitor() {
      @Override
      public void term(long term) {
        currentTerm = Math.max(currentTerm, term);
      }

      @Override
      public void vote(Optional<Replica> vote) {
        votedFor = vote;
      }

      @Override
      public void commit(long commit) {
        commitIndex = Math.max(commitIndex, commit);
      }

      @Override
      public void append(Entry entry, long index) {
        lastLogIndex = Math.max(index, lastLogIndex);
        lastLogTerm =  Math.max(entry.getTerm(), lastLogTerm);
        log.put(index, entry);
      }
    });

    fireComitted(null);

    LOGGER.info("Finished replaying log lastIndex {}, currentTerm {}, commitIndex {}, lastVotedFor {}",
      lastLogIndex, currentTerm, commitIndex, votedFor.orNull());
  }

  private void storeEntry(final long index, @Nonnull Entry entry) {
    LOGGER.debug("{}", entry);

    if (index % 100 == 0) {
      try {
        File snapshot = File.createTempFile("snapshot", "bin");
        journal.appendSnapshot(snapshot, lastLogIndex, lastLogTerm);
        final FileOutputStream fos = new FileOutputStream(snapshot);
        ListenableFuture snap = stateMachine.takeSnapshot(fos);
        Futures.addCallback(snap, new FutureCallback() {

          @Override
          public void onSuccess(@Nullable Object result) {
            //log.headMap(index, false).clear();
            journal.removeBefore(index);
          }

          @Override
          public void onFailure(Throwable t) {
            // FUCK!
          }

        }, executor);
      } catch (IOException e) {
        throw propagate(e);
      }

    }

    journal.appendEntry(entry, index);
    log.put(index, entry);
  }

  public long append(@Nonnull byte[] operation) {

    long index = ++lastLogIndex;
    lastLogTerm = currentTerm;

    Entry entry =
      Entry.newBuilder()
        .setCommand(ByteString.copyFrom(operation))
        .setTerm(currentTerm)
        .build();

    storeEntry(index, entry);

    return index;


  }

  public boolean append(@Nonnull AppendEntries appendEntries) {

    final long prevLogIndex = appendEntries.getPrevLogIndex();
    final long prevLogTerm = appendEntries.getPrevLogTerm();
    final List<Entry> entries = appendEntries.getEntriesList();

    if ((prevLogIndex > 0) && (!log.containsKey(prevLogIndex) || log.get(prevLogIndex).getTerm() != prevLogTerm)) {
      LOGGER.debug("Append prevLogIndex {} prevLogTerm {}", prevLogIndex, prevLogTerm);
      return false;
    }

    journal.removeAfter(prevLogIndex);
    log.tailMap(prevLogIndex, false).clear();

    lastLogIndex = prevLogIndex;
    for (Entry entry : entries) {
      storeEntry(++lastLogIndex, entry);
      lastLogTerm = entry.getTerm();
    }

    return true;

  }

  @Nonnull
  public GetEntriesResult getEntriesFrom(@Nonnegative long beginningIndex, @Nonnegative int max) {

    checkArgument(beginningIndex >= 0);

    long previousIndex = beginningIndex - 1;
    Entry previous = previousIndex <= 0 ? SENTINEL : log.get(previousIndex);
    Iterable<Entry> entries = FluentIterable.from(log.tailMap(beginningIndex).values()).limit(max);

    return new GetEntriesResult(previous.getTerm(), previousIndex, entries);

  }

  void fireComitted(Map<Long, SettableFuture<Object>> listeners) {
    try {
      for (long i = lastApplied + 1; i <= Math.min(commitIndex, lastLogIndex); ++i, ++lastApplied) {
        byte[] rawCommand = log.get(i).getCommand().toByteArray();
        final ByteBuffer operation = ByteBuffer.wrap(rawCommand).asReadOnlyBuffer();
        final ListenableFuture<Object> future = stateMachine.dispatchOperation(operation);

        if (listeners != null) {
          final SettableFuture<Object> listener = listeners.get(i);
          if (listener != null) {
            future.addListener(new Runnable() {
              @Override
              public void run() {
                try {
                  Object result = future.get();
                  listener.set(result);
                } catch (Throwable t) {
                  listener.setException(t);
                }
              }
            }, executor);
          }
        }
      }
    } catch (Exception e) {
      throw propagate(e);
    }
  }

  public long lastLogIndex() {
    return lastLogIndex;
  }

  public long lastLogTerm() {
    return lastLogTerm;
  }

  public long commitIndex() {
    return commitIndex;
  }

  public void commitIndex(long index, Map<Long, SettableFuture<Object>> listeners) {
    commitIndex = index;
    journal.appendCommit(index);
    fireComitted(listeners);
  }

  public long currentTerm() {
    return currentTerm;
  }

  public void currentTerm(@Nonnegative long term) {
    checkArgument(term >= 0);
    MDC.put("term", Long.toString(term));
    LOGGER.debug("New term {}", term);
    currentTerm = term;
    journal.appendTerm(term);
  }

  @Nonnull
  public Optional<Replica> lastVotedFor() {
    return votedFor;
  }

  public void lastVotedFor(@Nonnull Optional<Replica> vote) {
    LOGGER.debug("Voting for {}", vote.orNull());
    votedFor = vote;
    journal.appendVote(vote);
  }

  @Nonnull
  public Replica self() {
    return config.local();
  }

  @Nonnull
  public List<Replica> members() {
    return unmodifiableList(newArrayList(config.remote()));
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(getClass())
      .add("lastLogIndex", lastLogIndex)
      .add("lastApplied", lastApplied)
      .add("commitIndex", commitIndex)
      .add("lastVotedFor", votedFor)
      .toString();
  }

}
