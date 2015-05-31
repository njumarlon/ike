package org.allenai.dictionary.ml.subsample

import java.util

import nl.inl.blacklab.search.Span
import nl.inl.blacklab.search.lucene.{ HitQueryContext, BLSpans }
import nl.inl.blacklab.search.sequences.PerDocumentSortedSpans
import org.apache.lucene.util.PriorityQueue

/** Disjunction of spans that ensures that its matches are unique. Sets a capture group to the span
  * it returned. The capture span is negated if firstSpan could not match that capture group.
  *
  * @param firstSpan Span in disjunction that returns positive capture groups
  * @param alternatives Spans that return negated capture groups
  * @param captureName Name of the capture group to fill
  */
class SpansTrackingDisjunction(
    firstSpan: BLSpans,
    alternatives: Seq[BLSpans],
    captureName: String,
    matchEdge: Boolean = false
) extends BLSpans {

  /* Contains BLSpans marked with whether it is the firstSpan or not */
  case class SortedSpans(spans: BLSpans, first: Boolean)

  class SpanQueue(size: Int) extends PriorityQueue[SortedSpans](size) {

    override def lessThan(spans1: SortedSpans, spans2: SortedSpans): Boolean = {
      if (spans1.spans.doc() == spans2.spans.doc()) {
        if (spans1.spans.start() == spans2.spans.start()) {
          if (spans1.spans.end() == spans2.spans.end()) {
            spans1.first // firstSpan is smallest so it matches first if possible
          } else {
            spans1.spans.end() < spans2.spans.end()
          }
        } else {
          spans1.spans.start() < spans2.spans.start()
        }
      } else {
        spans1.spans.doc() < spans2.spans.doc()
      }
    }
  }

  var more = true
  var queue: SpanQueue = null

  // Where to store our capture group, set by setHitQueryContext
  var captureGroupIndex = -1

  def initialize(): Boolean = {
    // Can't initialize the queue until the spans are started so we do it here
    val firstSpanHasNext = firstSpan.next()
    val firstSpanSorted = if (firstSpan.hitsStartPointSorted()) {
      firstSpan
    } else {
      new PerDocumentSortedSpans(firstSpan, false, false)
    }
    val alternativeSorted = alternatives.filter(_.next()).map { spans =>
      if (!spans.hitsStartPointSorted()) {
        new PerDocumentSortedSpans(spans, false, false)
      } else {
        spans
      }
    }
    val allSpans = if (firstSpanHasNext) {
      SortedSpans(firstSpanSorted, first = true) +:
        alternativeSorted.map(SortedSpans(_, first = false))
    } else {
      alternativeSorted.map(SortedSpans(_, first = false))
    }
    queue = new SpanQueue(allSpans.size)
    allSpans.foreach(queue.add)
    queue.size() > 0
  }

  override def next(): Boolean = {
    if (more) {
      more = {
        if (queue == null) {
          initialize()
        } else {
          val (prevStart, prevDoc, prevEnd) = (start(), doc(), end())
          do {
            if (top.next()) {
              queue.updateTop()
            } else {
              queue.pop()
            }
          } while (queue.size() != 0 && prevStart == start && prevEnd == end && prevDoc == doc)
          queue.size() != 0
        }
      }
    }
    more
  }

  override def skipTo(target: Int): Boolean = {
    if (more) {
      var stepTaken = if (queue == null) {
        more = initialize()
        true
      } else {
        false
      }
      while (queue.size() != 0 && top.doc() < target) {
        if (top.skipTo(target)) {
          queue.updateTop()
        } else {
          queue.pop()
        }
        stepTaken = true
      }
      more = if (stepTaken) queue.size() != 0 else next()
    }
    more
  }

  def top: BLSpans = queue.top().spans
  def doc: Int = top.doc()
  def start: Int = top.start()
  def end: Int = top.end()

  override def setHitQueryContext(context: HitQueryContext): Unit = {
    captureGroupIndex = context.registerCapturedGroup(captureName)
  }

  override def passHitQueryContextToClauses(context: HitQueryContext): Unit = {
    firstSpan.setHitQueryContext(context)
    alternatives.foreach(_.setHitQueryContext(context))
  }

  override def getCapturedGroups(capturedGroups: Array[Span]): Unit = {
    if (queue.top().first) {
      capturedGroups.update(captureGroupIndex, new Span(firstSpan.start, firstSpan.end))
    } else {
      capturedGroups.update(captureGroupIndex, new Span(-top.start, -top.end))
    }
  }

  override val hitsAllSameLength: Boolean =
    firstSpan.hitsAllSameLength() && alternatives.forall(_.hitsLength() == firstSpan.hitsLength())
  override val hitsLength: Int = if (hitsAllSameLength) firstSpan.hitsLength() else -1
  override val hitsEndPointSorted = hitsAllSameLength
  override val hitsStartPointSorted = true
  override val hitsHaveUniqueEnd = hitsAllSameLength
  override val hitsHaveUniqueStart = hitsAllSameLength
  override val hitsAreUnique = true

  override def getPayload: util.Collection[Array[Byte]] = {
    val topSpans = top
    if (topSpans != null && topSpans.isPayloadAvailable) {
      topSpans.getPayload
    } else {
      new util.ArrayList[Array[Byte]]()
    }
  }

  override def isPayloadAvailable: Boolean = {
    val topSpans = top
    topSpans != null && topSpans.isPayloadAvailable
  }
}
