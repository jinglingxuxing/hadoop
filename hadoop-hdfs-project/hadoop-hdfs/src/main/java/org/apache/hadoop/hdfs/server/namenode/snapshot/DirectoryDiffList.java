/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode.snapshot;

import org.apache.hadoop.hdfs.server.namenode.INodeDirectory;
import org.apache.hadoop.hdfs.server.namenode.snapshot.
    DirectoryWithSnapshotFeature.DirectoryDiff;
import org.apache.hadoop.hdfs.server.namenode.snapshot.
    DirectoryWithSnapshotFeature.ChildrenDiff;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SkipList is an implementation of a data structure for storing a sorted list
 * of Directory Diff elements, using a hierarchy of linked lists that connect
 * increasingly sparse subsequences(defined by skip interval here) of the diffs.
 * The elements contained in the tree must be mutually comparable.
 * <p>
 * Consider  a case where we have 10 snapshots for a directory starting from s0
 * to s9 each associated with certain change records in terms of inodes deleted
 * and created after a particular snapshot and before the next snapshot. The
 * sequence will look like this:
 * <p>
 * s0->s1->s2->s3->s4->s5->s6->s7->s8->s9.
 * <p>
 * Assuming a skip interval of 3, which means a new diff will be added at a
 * level higher than the current level after we have  ore than 3 snapshots.
 * Next level promotion happens after 9 snapshots and so on.
 * <p>
 * level 2:   s08------------------------------->s9
 * level 1:   S02------->s35-------->s68-------->s9
 * level 0:  s0->s1->s2->s3->s4->s5->s6->s7->s8->s9
 * <p>
 * s02 will be created by combining diffs for s0, s1, s2 once s3 gets created.
 * Similarly, s08 will be created by combining s02, s35 and s68 once s9 gets
 * created.So, for constructing the children list fot s0, we have  to combine
 * s08, s9 and reverse apply to the live fs.
 * <p>
 * Similarly, for constructing the children list for s2, s2, s35, s68 and s9
 * need to get combined(or added) and reverse applied to current fs.
 * <p>
 * This approach will improve the snapshot deletion and snapshot diff
 * calculation.
 * <p>
 * Once a snapshot gets deleted, the list needs to be balanced.
 */
public class DirectoryDiffList implements DiffList<DirectoryDiff> {

  private static class SkipDiff {
    /**
     * The references to the subsequent nodes.
     */
    private SkipListNode skipTo;
    /**
     * combined diff over a skip Interval.
     */
    private ChildrenDiff diff;

    SkipDiff(ChildrenDiff diff) {
      this.diff = diff;
    }

    public ChildrenDiff getDiff() {
      return diff;
    }

    public SkipListNode getSkipTo() {
      return skipTo;
    }

    public void setSkipTo(SkipListNode node) {
      skipTo = node;
    }

    public void setDiff(ChildrenDiff diff) {
      this.diff = diff;
    }
  }
  /**
   * SkipListNode is an implementation of a DirectoryDiff List node,
   * which stores a Directory Diff and references to subsequent nodes.
   */
  private final static class SkipListNode implements Comparable<Integer> {

    /**
     * The data element stored in this node.
     */
    private DirectoryDiff diff;

    /**
     * List containing combined children diffs over a skip interval.
     */
    private List<SkipDiff> skipDiffList;

    /**
     * Constructs a new instance of SkipListNode with the specified data element
     * and level.
     *
     * @param diff The element to be stored in the node.
     */
    SkipListNode(DirectoryDiff diff, int level) {
      this.diff = diff;
      skipDiffList = new ArrayList<>(level + 1);
    }

    /**
     * Returns the level of this SkipListNode.
     */
    public int level() {
      return skipDiffList.size() - 1;
    }

    public DirectoryDiff getDiff() {
      return diff;
    }

    /**
     * Compare diffs with snapshot ID.
     */
    @Override
    public int compareTo(Integer that) {
      return diff.compareTo(that);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SkipListNode that = (SkipListNode) o;
      return Objects.equals(diff, that.diff);
    }

    @Override
    public int hashCode() {
      return Objects.hash(diff);
    }

    public void setSkipDiff(ChildrenDiff cDiff, int level) {
      if (level < skipDiffList.size()) {
        skipDiffList.get(level).setDiff(cDiff);
      } else {
        skipDiffList.add(new SkipDiff(cDiff));
      }
    }

    public void setSkipTo(SkipListNode node, int level) {
      for (int i = skipDiffList.size(); i <= level; i++) {
        skipDiffList.add(new SkipDiff(null));
      }
      skipDiffList.get(level).setSkipTo(node);
    }

    public ChildrenDiff getChildrenDiff(int level) {
      if (level == 0) {
        return diff.getChildrenDiff();
      } else {
        return skipDiffList.get(level).getDiff();
      }
    }

    SkipListNode getSkipNode(int level) {
      if (level >= skipDiffList.size()) {
        return null;
      } else {
        return skipDiffList.get(level).getSkipTo();
      }
    }
  }

  /**
   * The reference to the first node of the list.
   * The list will grow linearly once a new Directory diff gets added.
   * All the list inteface defined methods provide a linear view of the list.
   */
  private List<SkipListNode> skipNodeList;

  /**
   * The max no of skipLevels.
   */
  private final int maxSkipLevels;

  /**
   * The no of diffs after which the level promotion happens.
   */
  private final int skipInterval;

  /**
   * The head node to the list.
   */
  private SkipListNode head;

  /**
   * Constructs a new, empty instance of SkipList.
   */
  public DirectoryDiffList(int capacity, int interval, int skipLevel) {
    skipNodeList = new ArrayList<>(capacity);
    head = new SkipListNode(null, 0);
    this.maxSkipLevels = skipLevel;
    this.skipInterval = interval;
  }

  public static DirectoryDiffList createSkipList(int capacity, int interval,
      int skipLevel) {
    DirectoryDiffList list =
        new DirectoryDiffList(capacity, interval, skipLevel);
    return list;
  }

  /**
   * Adds the specified data element to the beginning of the SkipList,
   * if the element is not already present.
   * @param diff the element to be inserted
   */
  @Override
  public void addFirst(DirectoryDiff diff) {
    final int nodeLevel = randomLevel(skipInterval, maxSkipLevels);
    final SkipListNode[] nodePath = new SkipListNode[nodeLevel + 1];

    Arrays.fill(nodePath, head);
    for (int level = head.level() + 1; level <= nodeLevel; level++) {
      head.skipDiffList.add(new SkipDiff(null));
    }

    final SkipListNode newNode = new SkipListNode(diff, nodeLevel);
    for (int level = 0; level <= nodeLevel; level++) {
      if (level > 0) {
        // Case : S0 is added at the beginning and it has 3 levels
        //  suppose the list is like:
        //  level 1: head ------------------->s5------------->NULL
        //  level 0:head->    s1->s2->s3->s4->s5->s6->s7->s8->s9
        //  in this case:
        //  level 2: head -> s0 -------------------------------->NULL
        //  level 1: head -> s0'---------------->s5------------->NULL
        //  level 0:head->   s0->s1->s2->s3->s4->s5->s6->s7->s8->s9
        //  At level 1, we need to combine s0, s1, s2, s3, s4 and s5 and store
        //  as s0'. At level 2, s0 of next is pointing to null;
        //  Note: in this case, the diff of element being added is included
        //  while combining the diffs.
        final SkipListNode nextNode = head.getSkipNode(level);
        if (nextNode != null) {
          ChildrenDiff combined = combineDiff(newNode, nextNode, level);
          if (combined != null) {
            newNode.setSkipDiff(combined, level);
          }
        }
      }
      //insert to the linked list
      newNode.setSkipTo(nodePath[level].getSkipNode(level), level);
      nodePath[level].setSkipTo(newNode, level);
    }
    skipNodeList.add(0, newNode);
  }

  /**
   * Adds the specified data element to the end of the SkipList,
   * if the element is not already present.
   * @param diff the element to be inserted
   */
  @Override
  public boolean addLast(DirectoryDiff diff) {
    final int nodeLevel = randomLevel(skipInterval, maxSkipLevels);
    final SkipListNode[] nodePath = new SkipListNode[nodeLevel + 1];
    SkipListNode cur = head;
    final int headLevel = head.level();
    for (int level = headLevel < nodeLevel ? headLevel : nodeLevel;
         level >= 0; level--) {
      while (cur.getSkipNode(level) != null) {
        cur = cur.getSkipNode(level);
      }
      nodePath[level] = cur;
    }
    for (int level = headLevel + 1; level <= nodeLevel; level++) {
      head.skipDiffList.add(new SkipDiff(null));
      nodePath[level] = head;
    }

    final SkipListNode current = new SkipListNode(diff, nodeLevel);
    for (int level = 0; level <= nodeLevel; level++) {
      if (level > 0 && nodePath[level] != head) {
        //  suppose the list is like:
        //  level 2: head ->  s1----------------------------->NULL
        //  level 1: head ->  s1---->s3'------>s5------------->NULL
        //  level 0:head->    s1->s2->s3->s4->s5->s6->s7->s8->s9

        // case : s10 is added at the end the let the level for this node = 4
        //  in this case,
        //  level 2: head ->  s1''------------------------------------>s10
        //  level 1: head ->  s1'---->s3'------>s5'-------------------->s10
        //  level 0:head->    s1->s2->s3->s4->s5->s6->s7->s8->s9---->s10
        //  At level 1, we combine s5, s6, s7, s8, s9 and store as s5'
        //  At level 2, we combine s1', s3', s5' and form s1'' and store at s1.
        // Note : the last element(elemnt being added) diff is not added while
        // combining the diffs.
        ChildrenDiff combined = combineDiff(nodePath[level], current, level);
        if (combined != null) {
          nodePath[level].setSkipDiff(combined, level);
        }
      }
      nodePath[level].setSkipTo(current, level);
    }
    return skipNodeList.add(current);
  }

  private static ChildrenDiff combineDiff(SkipListNode from, SkipListNode to,
      int level) {
    ChildrenDiff combined = null;
    SkipListNode cur = from;
    for (int i = level - 1; i >= 0; i--) {
      while (cur != to) {
        final SkipListNode next = cur.getSkipNode(i);
        if (next == null) {
          break;
        }
        if (combined == null) {
          combined = new ChildrenDiff();
        }
        combined.combinePosterior(cur.getChildrenDiff(i), null);
        cur = next;
      }
    }
    return combined;
  }

  /**
   * Returns the data element at the specified index in this SkipList.
   *
   * @param index The index of the element to be returned.
   * @return The element at the specified index in this SkipList.
   */
  @Override
  public DirectoryDiff get(int index) {
    return skipNodeList.get(index).getDiff();
  }

  /**
   * Removes the element at the specified position in this list.
   *
   * @param index the index of the element to be removed
   * @throws UnsupportedOperationException {@inheritDoc}
   */
  @Override
  public DirectoryDiff remove(int index) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns true if this SkipList contains no data elements. In other words,
   * returns true if the size of this SkipList is zero.
   *
   * @return True if this SkipList contains no elements.
   */
  @Override
  public boolean isEmpty() {
    return skipNodeList.isEmpty();
  }

  /**
   * Returns the number of data elements in this SkipList.
   *
   * @return The number of elements in this SkipList.
   */
  @Override
  public int size() {
    return skipNodeList.size();
  }

  /**
   * Iterator is an iterator over the SkipList. This should
   * always provide a linear view of the list.
   */
  @Override
  public Iterator<DirectoryDiff> iterator() {
    final Iterator<SkipListNode> i = skipNodeList.iterator();
    return new Iterator<DirectoryDiff>() {

      @Override
      public boolean hasNext() {
        return i.hasNext();
      }

      @Override
      public DirectoryDiff next() {
        return i.next().getDiff();
      }
    };
  }

  @Override
  public int binarySearch(int key) {
    return Collections.binarySearch(skipNodeList, key);
  }

  private SkipListNode getNode(int index) {
    return skipNodeList.get(index);
  }

  /**
   * Returns the level of the skipList node.
   *
   * @param skipInterval The max interval after which the next level promotion
   *                     should happen.
   * @param maxLevel     Maximum no of skip levels
   * @return A value in the range 0 to maxLevel-1.
   */
  static int randomLevel(int skipInterval, int maxLevel) {
    final Random r = ThreadLocalRandom.current();
    for (int level = 0; level < maxLevel; level++) {
      // skip to the next level with probability 1/skipInterval
      if (r.nextInt(skipInterval) > 0) {
        return level;
      }
    }
    return maxLevel;
  }

  /**
   * This function returns the minimal set of diffs required to combine in
   * order to generate all the changes occurred between fromIndex and
   * toIndex.
   *
   * @param fromIndex index from where the summation has to start
   * @param toIndex   index till where the summation has to end
   * @return list of Directory Diff
   */
  @Override
  public List<DirectoryDiff> getMinListForRange(int fromIndex, int toIndex,
      INodeDirectory dir) {
    final List<DirectoryDiff> subList = new ArrayList<>();
    final int toSnapshotId = get(toIndex).getSnapshotId();
    for (SkipListNode current = getNode(fromIndex); current != null;) {
      SkipListNode next = null;
      ChildrenDiff childrenDiff = null;
      for (int level = current.level(); level >= 0; level--) {
        next = current.getSkipNode(level);
        if (next != null && next.getDiff().compareTo(toSnapshotId) <= 0) {
          childrenDiff = current.getChildrenDiff(level);
          break;
        }
      }
      final DirectoryDiff curDiff = current.getDiff();
      subList.add(childrenDiff == null ? curDiff :
          new DirectoryDiff(curDiff.getSnapshotId(), dir, childrenDiff));

      current = next;
    }
    return subList;
  }
}
