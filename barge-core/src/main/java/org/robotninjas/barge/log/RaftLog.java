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

import com.google.common.base.Optional;
import com.google.common.util.concurrent.SettableFuture;
import org.robotninjas.barge.Replica;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static org.robotninjas.barge.proto.RaftProto.AppendEntries;

public interface RaftLog {

  long append(@Nonnull byte[] operation);

  boolean append(@Nonnull AppendEntries appendEntries);

  @Nonnull GetEntriesResult getEntriesFrom(@Nonnegative long begin, @Nonnegative int max);

  @Nonnull List<Replica> members();

  long lastLogIndex();

  long lastLogTerm();

  long commitIndex();

  long currentTerm();

  @Nonnull Replica self();

  @Nonnull Optional<Replica> lastVotedFor();

  void updateCommitIndex(long index, Map<Long, SettableFuture<Object>> listeners);

  void updateCurrentTerm(@Nonnegative long term);

  void updateVotedFor(@Nonnull Optional<Replica> candidate);

  void load();

}
