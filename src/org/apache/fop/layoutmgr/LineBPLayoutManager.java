/*
 * $Id$
 * Copyright (C) 2001 The Apache Software Foundation. All rights reserved.
 * For details on use and redistribution please refer to the
 * LICENSE file included with these sources.
 */

package org.apache.fop.layoutmgr;


import org.apache.fop.fo.FObj;
import org.apache.fop.fo.TextInfo;
import org.apache.fop.fo.PropertyManager;
import org.apache.fop.layout.MarginProps;
import org.apache.fop.layout.HyphenationProps;
import org.apache.fop.layout.hyphenation.Hyphenation;
import org.apache.fop.layout.hyphenation.Hyphenator;
import org.apache.fop.traits.BlockProps;
import org.apache.fop.area.Area;
import org.apache.fop.area.LineArea;
import org.apache.fop.area.MinOptMax;
import org.apache.fop.area.inline.InlineArea;
import org.apache.fop.fo.properties.TextAlign;

import org.apache.fop.area.inline.Word;
import org.apache.fop.area.inline.Space;
import org.apache.fop.area.inline.Character;

import java.util.ListIterator;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;


/**
 * BPLayoutManager for lines. It builds one or more lines containing
 * inline areas generated by its sub layout managers.
 */
public class LineBPLayoutManager extends InlineStackingBPLayoutManager {

    /**
     * Private class to store information about inline breaks.
     * Each value holds the start and end indexes into a List of
     * inline break positions.
     */
    // private static class LineBreakPosition implements Position {
    private static class LineBreakPosition extends LeafPosition {
        // int m_iPos;
        double m_dAdjust; // Percentage to adjust (stretch or shrink)
        int lineHeight;
        int baseline;

        LineBreakPosition(BPLayoutManager lm, int iBreakIndex,
                          double dAdjust, int lh, int bl) {
            super(lm, iBreakIndex);
            // m_iPos = iBreakIndex;
            m_dAdjust = dAdjust;
            lineHeight = lh;
            baseline = bl;
        }
    }


    /** Break positions returned by inline content. */
    private ArrayList m_vecInlineBreaks = new ArrayList();

    private BreakPoss m_prevBP = null; // Last confirmed break position
    private boolean m_bJustify = false; // True if fo:block text-align=JUSTIFY
    private int m_iTextIndent = 0;
    private int m_iIndents = 0;
    private HyphenationProps m_hyphProps;

    private int lineHeight;
    private int lead;
    private int follow;

    // inline start pos when adding areas
    int iStartPos = 0;    

    public LineBPLayoutManager(FObj fobj, List lms, int lh, int l, int f) {
        //super(fobj, lms.listIterator(), lh, l, f);
        super(fobj, lms.listIterator());
        lineHeight = lh;
        lead = l;
        follow = f;
        init(); // Normally done when started by parent!
    }

    protected void initProperties(PropertyManager propMgr) {
        MarginProps marginProps = propMgr.getMarginProps();
        m_iIndents = marginProps.startIndent + marginProps.endIndent;
        BlockProps blockProps = propMgr.getBlockProps();
        m_bJustify = (blockProps.textAlign == TextAlign.JUSTIFY);
        m_iTextIndent = blockProps.firstIndent;
        m_hyphProps = propMgr.getHyphenationProps();
    }


    /**
     * Call child layout managers to generate content as long as they
     * generate inline areas. If a block-level generating LM is found,
     * finish any line being filled and return to the parent LM.
     */
    public BreakPoss getNextBreakPoss(LayoutContext context,
                                      Position prevLineBP) {
        // Get a break from currently active child LM
        // Set up constraints for inline level managers

        BPLayoutManager curLM ; // currently active LM
        BreakPoss prevBP = null;
        BreakPoss bp = null; // proposed BreakPoss

        ArrayList vecPossEnd = new ArrayList();

        // IPD remaining in line
        MinOptMax availIPD = context.getStackLimit();

        // QUESTION: maybe LayoutContext holds the Properties which
        // come from block-level?

        LayoutContext inlineLC = new LayoutContext(context);

        clearPrevIPD();
        int iPrevLineEnd = m_vecInlineBreaks.size();

        while ((curLM = getChildLM()) != null) {
            // INITIALIZE LAYOUT CONTEXT FOR CALL TO CHILD LM
            // First break for the child LM in each of its areas
            boolean bFirstBPforLM = (m_vecInlineBreaks.isEmpty() ||
                                     (((BreakPoss) m_vecInlineBreaks.get(m_vecInlineBreaks.size() - 1)).
                                      getLayoutManager() != curLM));

            // Need previous breakpoint! ATTENTION when backing up for hyphenation!
            prevBP = (m_vecInlineBreaks.isEmpty()) ? null :
                     (BreakPoss) m_vecInlineBreaks.get(m_vecInlineBreaks.size() - 1);
            initChildLC(inlineLC, prevBP,
                        (m_vecInlineBreaks.size() == iPrevLineEnd),
                        bFirstBPforLM, new SpaceSpecifier(true));


            /* If first BP in this line but line is not first in this
             * LM and previous line end decision was not forced (LINEFEED),
             * then set the SUPPRESS_LEADING_SPACE flag.
             */
            inlineLC.setFlags(LayoutContext.SUPPRESS_LEADING_SPACE,
                              (m_vecInlineBreaks.size() == iPrevLineEnd &&
                               !m_vecInlineBreaks.isEmpty() &&
                               ((BreakPoss) m_vecInlineBreaks.get(m_vecInlineBreaks.size() - 1)).
                               isForcedBreak() == false));

            // GET NEXT POSSIBLE BREAK FROM CHILD LM
            // prevBP = bp;
            if ((bp = curLM.getNextBreakPoss(inlineLC, null)) != null) {
                // Add any space before and previous content dimension
                MinOptMax prevIPD = updatePrevIPD(bp, prevBP,
                                                  (m_vecInlineBreaks.size() == iPrevLineEnd),
                                                  inlineLC.isFirstArea());
                MinOptMax bpDim =
                  MinOptMax.add(bp.getStackingSize(), prevIPD);

                // check if this bp fits in line
                boolean bBreakOK = couldEndLine(bp);
                if (bBreakOK) {
                    /* Add any non-conditional trailing space, assuming we
                     * end the line here. If we can't break here, we just
                     * check if the content fits. */
                    bpDim.add(bp.resolveTrailingSpace(true));
                }
                // TODO: stop if linebreak is forced (NEWLINE)
                // PROBLEM: interaction with wrap which can be set
                // at lower levels!
                // System.err.println("BPdim=" + bpDim.opt);

                // Check if proposed area would fit in line
                if (bpDim.min > availIPD.max) {
                    // See if we have already found a potential break
                    //if (vecPossEnd.size() > 0) break;

                    // This break position doesn't fit
                    // TODO: If we are in nowrap, we use it as is!
                    if (m_bJustify || m_prevBP == null) {
                        // If we are already in a hyphenation loop, then stop.

                        if (inlineLC.tryHyphenate()) {
                            break;
                        }
                        // Otherwise, prepare to try hyphenation
                        if (!bBreakOK) {
                            // Make sure we collect the entire word!
                            m_vecInlineBreaks.add(bp);
                            continue;
                        }

                        inlineLC.setHyphContext(
                          getHyphenContext(m_prevBP, bp));
                        if (inlineLC.getHyphContext() == null)
                            break;
                        inlineLC.setFlags(LayoutContext.TRY_HYPHENATE,
                                          true);
                        // Reset to previous acceptable break
                        reset();
                    }
                    /* If we are not in justified text, we can end the line at
                     * prevBP.
                     */
                    else {
                        break;
                    }
                } else {
                    // Add the BP to the list whether or not we can break
                    m_vecInlineBreaks.add(bp);
                    // Handle end of this LM's areas
                    if (bBreakOK) {
                        m_prevBP = bp; // Save reference to this BP
                        if (bp.isForcedBreak()) {
                            break;
                        }
                        if (bpDim.max >= availIPD.min) {
                            /* This is a possible line BP (line could be filled)
                             * bpDim.max >= availIPD.min
                             * Keep this as a possible break, depending on
                             * "cost". We will choose lowest cost.
                             * Cost depends on stretch
                             * (ie, bpDim.opt closes to availIPD.opt), keeps
                             * and hyphenation.
                             */
                            vecPossEnd.add( new BreakCost(bp,
                                                          Math.abs(availIPD.opt - bpDim.opt)));
                        }
                        // Otherwise it's short
                    } else {
                        /* Can't end line here. */
                    }
                } // end of bpDim.min <= availIPD.max
            } // end of getNextBreakPoss!=null on current child LM
            else {
                /* The child LM can return a null BreakPoss if it has
                 * nothing (more) to layout. This can happen when backing
                 * up. Just try the next child LM.
                 */
            }
            if (inlineLC.tryHyphenate() &&
                    !inlineLC.getHyphContext().hasMoreHyphPoints()) {
                break;
            }
        } // end of while on child LM
        if ((curLM = getChildLM()) == null) {
            // No more content to layout!
            setFinished(true);
        }

        if (bp == null)
            return null;
        if (m_prevBP == null)
            m_prevBP = bp;

        // Choose the best break
        if (!bp.isForcedBreak() && vecPossEnd.size() > 0) {
            m_prevBP = getBestBP(vecPossEnd);
        }
        // Backup child LM if necessary
        if (bp != m_prevBP && !bp.couldEndLine()) {
            reset();
        }
        // Distribute space in the line
        MinOptMax actual;
        if (bp != m_prevBP) {
            MinOptMax mom = getPrevIPD(m_prevBP.getLayoutManager());
            if (mom != null) {
                actual = MinOptMax.add(m_prevBP.getStackingSize(), mom);
            } else {
                actual = m_prevBP.getStackingSize();
            }
        } else {
            actual = m_prevBP.getStackingSize();
        }
        // ATTENTION: make sure this hasn't gotten start space for next
        // LM added onto it!
        actual.add(m_prevBP.resolveTrailingSpace(true));
        //log.error("Target opt=" + availIPD.opt + " bp.opt=" +
        //                   actual.opt + " bp.max=" + actual.max + " bm.min=" +
        //                   actual.min);

        // Don't justify last line in the sequence or if forced line-end
        boolean bJustify = (m_bJustify && !m_prevBP.isForcedBreak() &&
                            !isFinished());
        return makeLineBreak(iPrevLineEnd, availIPD, actual, bJustify);
    }


    private void reset() {
        while (m_vecInlineBreaks.get(m_vecInlineBreaks.size() - 1) != m_prevBP) {
            m_vecInlineBreaks.remove(m_vecInlineBreaks.size() - 1);
        }
        reset(m_prevBP.getPosition());
    }

    protected boolean couldEndLine(BreakPoss bp) {
        if (bp.canBreakAfter()) {
            return true; // no keep, ends on break char
        } else if (bp.isSuppressible()) {
            // NOTE: except at end of content for this LM!!
            // Never break after only space chars or any other sequence
            // of areas which would be suppressed at the end of the line.
            return false;
        } else {
            // See if could break before next area
            // TODO: do we need to set anything on the layout context?
            LayoutContext lc = new LayoutContext(0);
            BPLayoutManager nextLM = getChildLM();
            return (nextLM == null || nextLM.canBreakBefore(lc));
        }
    }



    private BreakPoss getBestBP(ArrayList vecPossEnd) {
        if (vecPossEnd.size() == 1) {
            return ((BreakCost) vecPossEnd.get(0)).getBP();
        }
        // Choose the best break (use a sort on cost!)
        Iterator iter = vecPossEnd.iterator();
        int minCost = Integer.MAX_VALUE;
        BreakPoss bestBP = null;
        while (iter.hasNext()) {
            BreakCost bc = (BreakCost) iter.next();
            if (bc.getCost() < minCost) {
                minCost = bc.getCost();
                bestBP = bc.getBP();
            }
        }
        return bestBP;
    }

    /** Line area is always considered to act as a fence. */
    protected boolean hasLeadingFence(boolean bNotFirst) {
        return true;
    }

    /** Line area is always considered to act as a fence. */
    protected boolean hasTrailingFence(boolean bNotLast) {
        return true;
    }

    private HyphContext getHyphenContext(BreakPoss prevBP,
                                         BreakPoss newBP) {
        // Get a "word" to hyphenate by getting characters from all
        // pending break poss which are in m_vecInlineBreaks, starting
        // with the position just AFTER prevBP.getPosition()

        m_vecInlineBreaks.add(newBP);
        ListIterator bpIter =
          m_vecInlineBreaks. listIterator(m_vecInlineBreaks.size());
        while (bpIter.hasPrevious() && bpIter.previous() != prevBP)
            ;
        if (bpIter.next() != prevBP) {
            //log.error("findHyphenPoss: problem!");
            return null;
        }
        StringBuffer sbChars = new StringBuffer(30);
        while (bpIter.hasNext()) {
            BreakPoss bp = (BreakPoss) bpIter.next();
            if (bp.getLayoutManager() == prevBP.getLayoutManager()) {
                bp.getLayoutManager().getWordChars(sbChars,
                                                   prevBP.getPosition(), bp.getPosition());
            } else {
                bp.getLayoutManager().getWordChars(sbChars, null,
                                                   bp.getPosition());
            }
            prevBP = bp;
        }
        m_vecInlineBreaks.remove(m_vecInlineBreaks.size() - 1); // remove last
        //log.debug("Word to hyphenate: " + sbChars.toString());

        // Now find all hyphenation points in this word (get in an array of offsets)
        // hyphProps are from the block level?. Note that according to the spec,
        // they also "apply to" fo:character. I don't know what that means, since
        // if we change language in the middle of a "word", the effect would seem
        // quite strange! Or perhaps in that case, we say that it's several words.
        // We probably should bring the hyphenation props up from the actual
        // TextLM which generate the hyphenation buffer, since these properties
        // inherit and could be specified on an inline or wrapper below the block
        // level.
        Hyphenation hyph = Hyphenator.hyphenate(m_hyphProps.language,
                                                m_hyphProps.country, sbChars.toString(),
                                                m_hyphProps.hyphenationRemainCharacterCount,
                                                m_hyphProps.hyphenationPushCharacterCount);
        // They hyph structure contains the information we need
        // Now start from prevBP: reset to that position, ask that LM to get
        // a Position for the first hyphenation offset. If the offset isn't in
        // its characters, it returns null, but must tell how many chars it had.
        // Keep looking at currentBP using next hyphenation point until the
        // returned size is greater than the available size or no more hyphenation
        // points remain. Choose the best break.
        if (hyph != null) {
            return new HyphContext(hyph.getHyphenationPoints());
        } else
            return null;
    }


    private BreakPoss makeLineBreak(int prevLineEnd, MinOptMax target,
                                    MinOptMax actual, boolean bJustify) {
        // make a new BP
        // Store information needed to make areas in the LineBreakPosition!
        // Calculate stretch or shrink factor

        double dAdjust = 0.0;
        if (bJustify) {
            if (actual.opt < target.opt) {
                // Stretch
                dAdjust = (double)(target.opt - actual.opt) /
                          (double)(actual.max - actual.opt);
            } else {
                // Shrink
                dAdjust = (double)(target.opt - actual.opt) /
                          (double)(actual.opt - actual.min);
            }
        }

        // lead to baseline is
        // max of: baseline fixed alignment and middle/2
        // after baseline is
        // max: top height-lead, middle/2 and bottom height-lead
        int halfLeading = (lineHeight - lead - follow) / 2;
        // height before baseline
        int lineLead = lead + halfLeading;
        // maximum size of top and bottom alignment
        int maxtb = follow + halfLeading;
        // max size of middle alignment below baseline
        int middlefollow = maxtb;
        for(Iterator iter = m_vecInlineBreaks.listIterator(prevLineEnd);
                iter.hasNext(); ) {
            BreakPoss bp = (BreakPoss)iter.next();
            if(bp.getLead() > lineLead) {
                lineLead = bp.getLead();
            } 
            if(bp.getTotal() > maxtb) {
                maxtb = bp.getTotal();
            }
            if(bp.getMiddle() > middlefollow) {
                middlefollow = bp.getMiddle();
            } 
        }
        if(maxtb - lineLead > middlefollow) {
            middlefollow = maxtb - lineLead;
        }

        //log.debug("Adjustment factor=" + dAdjust);
        BreakPoss curLineBP = new BreakPoss( new LineBreakPosition(this,
                                             m_vecInlineBreaks.size() - 1, dAdjust, lineLead + middlefollow, lineLead));

        curLineBP.setFlag(BreakPoss.ISLAST, isFinished());
        curLineBP.setStackingSize(new MinOptMax(lineLead + middlefollow));
        return curLineBP;
    }

    public void resetPosition(Position resetPos) {
        if (resetPos == null) {
            reset(null);
            m_vecInlineBreaks.clear();
            m_prevBP = null;
        } else {
            m_prevBP = (BreakPoss)m_vecInlineBreaks.get(((LineBreakPosition)resetPos).getLeafPos());
            while (m_vecInlineBreaks.get(m_vecInlineBreaks.size() - 1) != m_prevBP)
{
                m_vecInlineBreaks.remove(m_vecInlineBreaks.size() - 1);
            }
            reset(m_prevBP.getPosition());
        }
    }

    public void addAreas(PositionIterator parentIter,
                         LayoutContext context) {
        addAreas(parentIter, 0.0);

        //m_vecInlineBreaks.clear();
        m_prevBP = null;
    }

    // Generate and add areas to parent area
    // Set size etc
    // dSpaceAdjust should reference extra space in the BPD
    public void addAreas(PositionIterator parentIter, double dSpaceAdjust) {
        BPLayoutManager childLM ;
        //int iStartPos = 0;
        LayoutContext lc = new LayoutContext(0);
        while (parentIter.hasNext()) {
            LineBreakPosition lbp = (LineBreakPosition) parentIter.next();
            LineArea lineArea = new LineArea();
            lineArea.setHeight(lbp.lineHeight);
            lc.setBaseline(lbp.baseline);
            lc.setLineHeight(lbp.lineHeight);
            setCurrentArea(lineArea);
            // Add the inline areas to lineArea
            PositionIterator inlinePosIter =
              new BreakPossPosIter(m_vecInlineBreaks, iStartPos,
                                   lbp.getLeafPos() + 1);
            iStartPos = lbp.getLeafPos() + 1;
            lc.setSpaceAdjust(lbp.m_dAdjust);
            lc.setLeadingSpace(new SpaceSpecifier(true));
            lc.setFlags(LayoutContext.RESOLVE_LEADING_SPACE, true);
            setChildContext(lc);
            while ((childLM = inlinePosIter.getNextChildLM()) != null) {
                childLM.addAreas(inlinePosIter, lc);
                lc.setLeadingSpace(lc.getTrailingSpace());
                lc.setTrailingSpace(new SpaceSpecifier(false));
            }
if(lc.getTrailingSpace() != null) {
            addSpace(lineArea, lc.getTrailingSpace().resolve(true),
                     lc.getSpaceAdjust());
}
            parentLM.addChild(lineArea);
        }
        setCurrentArea(null); // ?? necessary
    }

}

