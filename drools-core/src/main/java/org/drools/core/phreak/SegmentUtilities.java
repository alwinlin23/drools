/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.drools.core.phreak;

import org.drools.core.common.Memory;
import org.drools.core.common.MemoryFactory;
import org.drools.core.common.NetworkNode;
import org.drools.core.common.ReteEvaluator;
import org.drools.core.reteoo.AccumulateNode.AccumulateMemory;
import org.drools.core.reteoo.AlphaNode;
import org.drools.core.reteoo.AsyncReceiveNode;
import org.drools.core.reteoo.AsyncSendNode;
import org.drools.core.reteoo.BetaMemory;
import org.drools.core.reteoo.BetaNode;
import org.drools.core.reteoo.ConditionalBranchNode;
import org.drools.core.reteoo.ConditionalBranchNode.ConditionalBranchMemory;
import org.drools.core.reteoo.EntryPointNode;
import org.drools.core.reteoo.EvalConditionNode;
import org.drools.core.reteoo.EvalConditionNode.EvalMemory;
import org.drools.core.reteoo.ExistsNode;
import org.drools.core.reteoo.FromNode;
import org.drools.core.reteoo.LeftInputAdapterNode;
import org.drools.core.reteoo.LeftInputAdapterNode.LiaNodeMemory;
import org.drools.core.reteoo.LeftTupleNode;
import org.drools.core.reteoo.LeftTupleSink;
import org.drools.core.reteoo.LeftTupleSinkNode;
import org.drools.core.reteoo.LeftTupleSinkPropagator;
import org.drools.core.reteoo.LeftTupleSource;
import org.drools.core.reteoo.NodeTypeEnums;
import org.drools.core.reteoo.NotNode;
import org.drools.core.reteoo.ObjectSink;
import org.drools.core.reteoo.ObjectSource;
import org.drools.core.reteoo.ObjectTypeNode;
import org.drools.core.reteoo.PathMemory;
import org.drools.core.reteoo.QueryElementNode;
import org.drools.core.reteoo.QueryElementNode.QueryElementNodeMemory;
import org.drools.core.reteoo.RightInputAdapterNode;
import org.drools.core.reteoo.RightInputAdapterNode.RiaNodeMemory;
import org.drools.core.reteoo.SegmentMemory;
import org.drools.core.reteoo.TerminalNode;
import org.drools.core.reteoo.TimerNode;
import org.drools.core.reteoo.TimerNode.TimerNodeMemory;
import org.drools.core.rule.constraint.QueryNameConstraint;

public class SegmentUtilities {

    /**
     * Initialises the NodeSegment memory for all nodes in the segment.
     */
    public static SegmentMemory getOrCreateSegmentMemory(LeftTupleSource tupleSource, ReteEvaluator reteEvaluator) {
        SegmentMemory smem = reteEvaluator.getNodeMemory((MemoryFactory) tupleSource).getSegmentMemory();
        if ( smem != null ) {
            return smem;
        }

        // find segment root
        while (!SegmentUtilities.isRootNode(tupleSource, null)) {
            tupleSource = tupleSource.getLeftTupleSource();
        }

        LeftTupleSource segmentRoot = tupleSource;
        int nodeTypesInSegment = 0;

        smem = restoreSegmentFromPrototype(reteEvaluator, segmentRoot, nodeTypesInSegment);
        if ( smem != null ) {
            if (NodeTypeEnums.isBetaNode(segmentRoot) && ( (BetaNode) segmentRoot ).isRightInputIsRiaNode()) {
                createRiaSegmentMemory( (BetaNode) segmentRoot, reteEvaluator );
            }
            return smem;
        }

        smem = new SegmentMemory(segmentRoot);

        // Iterate all nodes on the same segment, assigning their position as a bit mask value
        // allLinkedTestMask is the resulting mask used to test if all nodes are linked in
        long nodePosMask = 1;
        long allLinkedTestMask = 0;
        boolean updateNodeBit = true;  // nodes after a branch CE can notify, but they cannot impact linking

        while (true) {
            nodeTypesInSegment = updateNodeTypesMask(tupleSource, nodeTypesInSegment);
            if (NodeTypeEnums.isBetaNode(tupleSource)) {
                allLinkedTestMask = processBetaNode((BetaNode)tupleSource, reteEvaluator, smem, nodePosMask, allLinkedTestMask, updateNodeBit);
            } else {
                switch (tupleSource.getType()) {
                    case NodeTypeEnums.LeftInputAdapterNode:
                        allLinkedTestMask = processLiaNode((LeftInputAdapterNode) tupleSource, reteEvaluator, smem, nodePosMask, allLinkedTestMask);
                        break;
                    case NodeTypeEnums.EvalConditionNode:
                        processEvalNode((EvalConditionNode) tupleSource, reteEvaluator, smem);
                        break;
                    case NodeTypeEnums.ConditionalBranchNode:
                        updateNodeBit = processBranchNode((ConditionalBranchNode) tupleSource, reteEvaluator, smem);
                        break;
                    case NodeTypeEnums.FromNode:
                        processFromNode((FromNode) tupleSource, reteEvaluator, smem);
                        break;
                    case NodeTypeEnums.ReactiveFromNode:
                        processReactiveFromNode((MemoryFactory) tupleSource, reteEvaluator, smem, nodePosMask);
                        break;
                    case NodeTypeEnums.TimerConditionNode:
                        processTimerNode((TimerNode) tupleSource, reteEvaluator, smem, nodePosMask);
                        break;
                    case NodeTypeEnums.AsyncSendNode:
                        processAsyncSendNode((AsyncSendNode) tupleSource, reteEvaluator, smem);
                        break;
                    case NodeTypeEnums.AsyncReceiveNode:
                        processAsyncReceiveNode((AsyncReceiveNode) tupleSource, reteEvaluator, smem, nodePosMask);
                        break;
                    case NodeTypeEnums.QueryElementNode:
                        updateNodeBit = processQueryNode((QueryElementNode) tupleSource, reteEvaluator, segmentRoot, smem, nodePosMask);
                        break;
                }
            }

            nodePosMask = nextNodePosMask(nodePosMask);

            if (tupleSource.getSinkPropagator().size() == 1) {
                LeftTupleSinkNode sink = tupleSource.getSinkPropagator().getFirstLeftTupleSink();
                if (NodeTypeEnums.isLeftTupleSource(sink)) {
                    tupleSource = (LeftTupleSource) sink;
                } else {
                    // rtn or rian
                    // While not technically in a segment, we want to be able to iterate easily from the last node memory to the ria/rtn memory
                    // we don't use createNodeMemory, as these may already have been created by, but not added, by the method updateRiaAndTerminalMemory
                    Memory memory = reteEvaluator.getNodeMemory((MemoryFactory) sink);
                    if (sink.getType() == NodeTypeEnums.RightInputAdaterNode) {
                        PathMemory riaPmem = ((RiaNodeMemory)memory).getRiaPathMemory();
                        smem.getNodeMemories().add( riaPmem );

                        RightInputAdapterNode rian = ( RightInputAdapterNode ) sink;
                        ObjectSink[] nodes = rian.getObjectSinkPropagator().getSinks();
                        for ( ObjectSink node : nodes ) {
                            if ( NodeTypeEnums.isLeftTupleSource(node) )  {
                                getOrCreateSegmentMemory( (LeftTupleSource) node, reteEvaluator );
                            }
                        }
                    } else if (NodeTypeEnums.isTerminalNode(sink)) {
                        smem.getNodeMemories().add(memory);
                    }
                    memory.setSegmentMemory(smem);
                    smem.setTipNode(sink);
                    break;
                }
            } else {
                // not in same segment
                smem.setTipNode(tupleSource);
                break;
            }
        }
        smem.setAllLinkedMaskTest(allLinkedTestMask);

        // iterate to find root and determine the SegmentNodes position in the RuleSegment
        LeftTupleSource pathRoot = segmentRoot;
        int ruleSegmentPosMask = 1;
        int counter = 0;
        while (pathRoot.getType() != NodeTypeEnums.LeftInputAdapterNode) {
            LeftTupleSource leftTupleSource = pathRoot.getLeftTupleSource();
            if (SegmentUtilities.isNonTerminalTipNode(leftTupleSource, null)) {
                // for each new found segment, increase the mask bit position
                ruleSegmentPosMask = ruleSegmentPosMask << 1;
                counter++;
            }
            pathRoot = leftTupleSource;
        }
        smem.setSegmentPosMaskBit(ruleSegmentPosMask);
        smem.setPos(counter);

        updateRiaAndTerminalMemory(tupleSource, tupleSource, smem, reteEvaluator, false, nodeTypesInSegment);

        reteEvaluator.getKnowledgeBase().registerSegmentPrototype(segmentRoot, smem);

        return smem;
    }

    public static long nextNodePosMask(long nodePosMask) {
        // prevent overflow of segment and path memories masks when a segment has 64 or more nodes or a path has 64 or more segments
        // in this extreme case all the items after the 64th will be all mapped by the same bit and then the linking of one of them
        // will be enough to consider all those item linked
        long nextNodePosMask = nodePosMask << 1;
        return nextNodePosMask > 0 ? nextNodePosMask : nodePosMask;
    }

    private static SegmentMemory restoreSegmentFromPrototype(ReteEvaluator reteEvaluator, LeftTupleSource segmentRoot, int nodeTypesInSegment) {
        SegmentMemory smem = reteEvaluator.getKnowledgeBase().createSegmentFromPrototype(reteEvaluator, segmentRoot);
        if ( smem != null ) {
            updateRiaAndTerminalMemory(segmentRoot, segmentRoot, smem, reteEvaluator, true, nodeTypesInSegment);
        }
        return smem;
    }

    private static boolean processQueryNode(QueryElementNode queryNode, ReteEvaluator reteEvaluator, LeftTupleSource segmentRoot, SegmentMemory smem, long nodePosMask) {
        // Initialize the QueryElementNode and have it's memory reference the actual query SegmentMemory
        SegmentMemory querySmem = getQuerySegmentMemory(reteEvaluator, segmentRoot, queryNode);
        QueryElementNodeMemory queryNodeMem = smem.createNodeMemory(queryNode, reteEvaluator);
        queryNodeMem.setNodePosMaskBit(nodePosMask);
        queryNodeMem.setQuerySegmentMemory(querySmem);
        queryNodeMem.setSegmentMemory(smem);
        return ! queryNode.getQueryElement().isAbductive();
    }

    public static SegmentMemory getQuerySegmentMemory(ReteEvaluator reteEvaluator, LeftTupleSource segmentRoot, QueryElementNode queryNode) {
        LeftInputAdapterNode liaNode = getQueryLiaNode(queryNode.getQueryElement().getQueryName(), getQueryOtn(segmentRoot));
        LiaNodeMemory liam = reteEvaluator.getNodeMemory(liaNode);
        SegmentMemory querySmem = liam.getSegmentMemory();
        if (querySmem == null) {
            querySmem = getOrCreateSegmentMemory(liaNode, reteEvaluator);
        }
        return querySmem;
    }

    private static void processFromNode(MemoryFactory tupleSource, ReteEvaluator reteEvaluator, SegmentMemory smem) {
        smem.createNodeMemory(tupleSource, reteEvaluator).setSegmentMemory(smem);
    }

    private static void processAsyncSendNode(MemoryFactory tupleSource, ReteEvaluator reteEvaluator, SegmentMemory smem) {
        smem.createNodeMemory(tupleSource, reteEvaluator).setSegmentMemory(smem);
    }

    private static void processAsyncReceiveNode(AsyncReceiveNode tupleSource, ReteEvaluator reteEvaluator, SegmentMemory smem, long nodePosMask) {
        AsyncReceiveNode.AsyncReceiveMemory tnMem = smem.createNodeMemory( tupleSource, reteEvaluator );
        tnMem.setNodePosMaskBit(nodePosMask);
        tnMem.setSegmentMemory(smem);
    }

    private static void processReactiveFromNode(MemoryFactory tupleSource, ReteEvaluator reteEvaluator, SegmentMemory smem, long nodePosMask) {
        FromNode.FromMemory mem = ((FromNode.FromMemory) smem.createNodeMemory(tupleSource, reteEvaluator));
        mem.setSegmentMemory(smem);
        mem.setNodePosMaskBit(nodePosMask);
    }

    private static boolean processBranchNode(ConditionalBranchNode tupleSource, ReteEvaluator reteEvaluator, SegmentMemory smem) {
        ConditionalBranchMemory branchMem = smem.createNodeMemory(tupleSource, reteEvaluator);
        branchMem.setSegmentMemory(smem);
        // nodes after a branch CE can notify, but they cannot impact linking
        return false;
    }

    private static void processEvalNode(EvalConditionNode tupleSource, ReteEvaluator reteEvaluator, SegmentMemory smem) {
        EvalMemory evalMem = smem.createNodeMemory(tupleSource, reteEvaluator);
        evalMem.setSegmentMemory(smem);
    }

    private static void processTimerNode(TimerNode tupleSource, ReteEvaluator reteEvaluator, SegmentMemory smem, long nodePosMask) {
        TimerNodeMemory tnMem = smem.createNodeMemory( tupleSource, reteEvaluator );
        tnMem.setNodePosMaskBit(nodePosMask);
        tnMem.setSegmentMemory(smem);
    }

    private static long processLiaNode(LeftInputAdapterNode tupleSource, ReteEvaluator reteEvaluator, SegmentMemory smem, long nodePosMask, long allLinkedTestMask) {
        LiaNodeMemory liaMemory = smem.createNodeMemory(tupleSource, reteEvaluator);
        liaMemory.setSegmentMemory(smem);
        liaMemory.setNodePosMaskBit(nodePosMask);
        allLinkedTestMask = allLinkedTestMask | nodePosMask;
        return allLinkedTestMask;
    }

    private static long processBetaNode(BetaNode betaNode, ReteEvaluator reteEvaluator, SegmentMemory smem, long nodePosMask, long allLinkedTestMask, boolean updateNodeBit) {
        BetaMemory bm = NodeTypeEnums.AccumulateNode == betaNode.getType() ?
                        ((AccumulateMemory) smem.createNodeMemory(betaNode, reteEvaluator)).getBetaMemory() :
                        (BetaMemory) smem.createNodeMemory(betaNode, reteEvaluator);

        // this must be set first, to avoid recursion as sub networks can be initialised multiple ways
        // and bm.getSegmentMemory == null check can be used to avoid recursion.
        bm.setSegmentMemory(smem);

        if (betaNode.isRightInputIsRiaNode()) {
            RightInputAdapterNode riaNode = createRiaSegmentMemory( betaNode, reteEvaluator );

            RiaNodeMemory riaMem = reteEvaluator.getNodeMemory(riaNode);
            bm.setRiaRuleMemory(riaMem.getRiaPathMemory());
            if (updateNodeBit && canBeDisabled(betaNode) && riaMem.getRiaPathMemory().getAllLinkedMaskTest() > 0) {
                // only ria's with reactive subnetworks can be disabled and thus need checking
                allLinkedTestMask = allLinkedTestMask | nodePosMask;
            }
        } else if (updateNodeBit && canBeDisabled(betaNode)) {
            allLinkedTestMask = allLinkedTestMask | nodePosMask;

        }
        bm.setNodePosMaskBit(nodePosMask);
        if (NodeTypeEnums.NotNode == betaNode.getType()) {
            // not nodes start up linked in
            smem.linkNodeWithoutRuleNotify(bm.getNodePosMaskBit());
        }
        return allLinkedTestMask;
    }

    private static RightInputAdapterNode createRiaSegmentMemory( BetaNode betaNode, ReteEvaluator reteEvaluator ) {
        RightInputAdapterNode riaNode = (RightInputAdapterNode) betaNode.getRightInput();

        LeftTupleSource subnetworkLts = riaNode.getLeftTupleSource();
        while (subnetworkLts.getLeftTupleSource() != riaNode.getStartTupleSource()) {
            subnetworkLts = subnetworkLts.getLeftTupleSource();
        }

        Memory rootSubNetwokrMem = reteEvaluator.getNodeMemory( (MemoryFactory) subnetworkLts );
        SegmentMemory subNetworkSegmentMemory = rootSubNetwokrMem.getSegmentMemory();
        if (subNetworkSegmentMemory == null) {
            // we need to stop recursion here
            getOrCreateSegmentMemory(subnetworkLts, reteEvaluator);
        }
        return riaNode;
    }

    private static boolean canBeDisabled(BetaNode betaNode) {
        // non empty not nodes and accumulates can never be disabled and thus don't need checking
        return (!(NodeTypeEnums.NotNode == betaNode.getType() && !((NotNode) betaNode).isEmptyBetaConstraints()) &&
                NodeTypeEnums.AccumulateNode != betaNode.getType() && !betaNode.isRightInputPassive());
    }

    public static void createChildSegments(ReteEvaluator reteEvaluator, SegmentMemory smem, LeftTupleSinkPropagator sinkProp) {
        if ( !smem.isEmpty() ) {
              return; // this can happen when multiple threads are trying to initialize the segment
        }
        for (LeftTupleSinkNode sink = sinkProp.getFirstLeftTupleSink(); sink != null; sink = sink.getNextLeftTupleSinkNode()) {
            SegmentMemory childSmem = createChildSegment(reteEvaluator, sink);
            childSmem.setPos( smem.getPos()+1 );
            smem.add(childSmem);
        }
    }

    public static SegmentMemory createChildSegment(ReteEvaluator reteEvaluator, LeftTupleNode node) {
        Memory memory = reteEvaluator.getNodeMemory((MemoryFactory) node);
        if (memory.getSegmentMemory() == null) {
            if (NodeTypeEnums.isEndNode(node)) {
                // RTNS and RiaNode's have their own segment, if they are the child of a split.
                createChildSegmentForTerminalNode( node, memory );
            } else {
                getOrCreateSegmentMemory((LeftTupleSource) node, reteEvaluator);
            }
        }
        return memory.getSegmentMemory();
    }

    public static SegmentMemory createChildSegmentForTerminalNode( LeftTupleNode node, Memory memory ) {
        SegmentMemory childSmem = new SegmentMemory( node ); // rtns or riatns don't need a queue
        PathMemory pmem = NodeTypeEnums.isTerminalNode( node ) ? (PathMemory) memory : ((RiaNodeMemory) memory).getRiaPathMemory();

        childSmem.setPos( pmem.getSegmentMemories().length - 1 );
        pmem.setSegmentMemory(childSmem.getPos(), childSmem);
        pmem.setSegmentMemory(childSmem);
        childSmem.addPathMemory( pmem );

        childSmem.setTipNode(node);
        return childSmem;
    }

    /**
     * Is the LeftTupleSource a node in the sub network for the RightInputAdapterNode
     * To be in the same network, it must be a node is after the two output of the parent
     * and before the rianode.
     */
    public static boolean inSubNetwork(RightInputAdapterNode riaNode, LeftTupleSource leftTupleSource) {
        LeftTupleSource startTupleSource = riaNode.getStartTupleSource();
        LeftTupleSource parent = riaNode.getLeftTupleSource();

        while (parent != startTupleSource) {
            if (parent == leftTupleSource) {
                return true;
            }
            parent = parent.getLeftTupleSource();
        }

        return false;
    }

    /**
     * This adds the segment memory to the terminal node or ria node's list of memories.
     * In the case of the terminal node this allows it to know that all segments from
     * the tip to root are linked.
     * In the case of the ria node its all the segments up to the start of the subnetwork.
     * This is because the rianode only cares if all of it's segments are linked, then
     * it sets the bit of node it is the right input for.
     */
    private static int updateRiaAndTerminalMemory( LeftTupleSource lt,
                                                   LeftTupleSource originalLt,
                                                   SegmentMemory smem,
                                                   ReteEvaluator reteEvaluator,
                                                   boolean fromPrototype,
                                                   int nodeTypesInSegment ) {

        nodeTypesInSegment = checkSegmentBoundary(lt, reteEvaluator, nodeTypesInSegment);

        PathMemory pmem = null;
        for (LeftTupleSink sink : lt.getSinkPropagator().getSinks()) {
            if (NodeTypeEnums.isLeftTupleSource(sink)) {
                nodeTypesInSegment = updateRiaAndTerminalMemory((LeftTupleSource) sink, originalLt, smem, reteEvaluator, fromPrototype, nodeTypesInSegment);
            } else if (sink.getType() == NodeTypeEnums.RightInputAdaterNode) {
                // Even though we don't add the pmem and smem together, all pmem's for all pathend nodes must be initialized
                RiaNodeMemory riaMem = (RiaNodeMemory) reteEvaluator.getNodeMemory((MemoryFactory) sink);
                // Only add the RIANode, if the LeftTupleSource is part of the RIANode subnetwork
                if (inSubNetwork((RightInputAdapterNode) sink, originalLt)) {
                    pmem = riaMem.getRiaPathMemory();

                    if (fromPrototype) {
                        ObjectSink[] nodes = ((RightInputAdapterNode) sink).getObjectSinkPropagator().getSinks();
                        for ( ObjectSink node : nodes ) {
                            // check if the SegmentMemory has been already created by the BetaNode and if so avoid to build it twice
                            if ( NodeTypeEnums.isLeftTupleSource(node) && reteEvaluator.getNodeMemory((MemoryFactory) node).getSegmentMemory() == null )  {
                                restoreSegmentFromPrototype(reteEvaluator, (LeftTupleSource) node, nodeTypesInSegment);
                            }
                        }
                    } else if ( ( pmem.getAllLinkedMaskTest() & ( 1L << pmem.getSegmentMemories().length ) ) == 0 ) {
                        // must eagerly initialize child segment memories
                        ObjectSink[] nodes = ((RightInputAdapterNode) sink).getObjectSinkPropagator().getSinks();
                        for ( ObjectSink node : nodes ) {
                            if ( NodeTypeEnums.isLeftTupleSource(node) )  {
                                getOrCreateSegmentMemory( (LeftTupleSource) node, reteEvaluator );
                            }
                        }
                    }
                }

            } else if (NodeTypeEnums.isTerminalNode(sink)) {
                pmem = (PathMemory) reteEvaluator.getNodeMemory((MemoryFactory) sink);
            }

            if (pmem != null && smem.getPos() < pmem.getSegmentMemories().length) {
                smem.addPathMemory( pmem );
                pmem.setSegmentMemory( smem.getPos(), smem );
                if (smem.isSegmentLinked()) {
                    // not's can cause segments to be linked, and the rules need to be notified for evaluation
                    smem.notifyRuleLinkSegment(reteEvaluator);
                }
                checkEagerSegmentCreation(sink.getLeftTupleSource(), reteEvaluator, nodeTypesInSegment);
                pmem = null;
            }
        }
        return nodeTypesInSegment;
    }

    private static int checkSegmentBoundary(LeftTupleSource lt, ReteEvaluator reteEvaluator, int nodeTypesInSegment) {
        if ( isRootNode( lt, null ) )  {
            // we are in a new child segment
            checkEagerSegmentCreation(lt.getLeftTupleSource(), reteEvaluator, nodeTypesInSegment);
            nodeTypesInSegment = 0;
        }
        return updateNodeTypesMask(lt, nodeTypesInSegment);
    }

    public static void checkEagerSegmentCreation(LeftTupleSource lt, ReteEvaluator reteEvaluator, int nodeTypesInSegment) {
        // A Not node has to be eagerly initialized unless in its segment there is at least a join node
        if ( isSet(nodeTypesInSegment, NOT_NODE_BIT) &&
             !isSet(nodeTypesInSegment, JOIN_NODE_BIT) &&
             !isSet(nodeTypesInSegment, REACTIVE_EXISTS_NODE_BIT) ) {
            getOrCreateSegmentMemory(lt, reteEvaluator);
        }
    }

    /**
     * Returns whether the node is the root of a segment.
     * Lians are always the root of a segment.
     *
     * node cannot be null.
     *
     * The result should discount any removingRule. That means it gives you the result as
     * if the rule had already been removed from the network.
     */
    public static boolean isRootNode(LeftTupleNode node, TerminalNode removingTN) {
        return node.getType() == NodeTypeEnums.LeftInputAdapterNode || isNonTerminalTipNode( node.getLeftTupleSource(), removingTN );
    }

    /**
     * Returns whether the node is the tip of a segment.
     * EndNodes (rtn and rian) are always the tip of a segment.
     *
     * node cannot be null.
     *
     * The result should discount any removingRule. That means it gives you the result as
     * if the rule had already been removed from the network.
     */
    public static boolean isTipNode( LeftTupleNode node, TerminalNode removingTN ) {
        return NodeTypeEnums.isEndNode(node) || isNonTerminalTipNode( node, removingTN );
    }

    public static boolean isNonTerminalTipNode( LeftTupleNode node, TerminalNode removingTN ) {
        LeftTupleSinkPropagator sinkPropagator = node.getSinkPropagator();

        if (removingTN == null) {
            return sinkPropagator.size() > 1;
        }

        if (sinkPropagator.size() == 1) {
            return false;
        }

        // we know the sink size is creater than 1 and that there is a removingRule that needs to be ignored.
        int count = 0;
        for ( LeftTupleSinkNode sink = sinkPropagator.getFirstLeftTupleSink(); sink != null; sink = sink.getNextLeftTupleSinkNode() ) {
            if ( sinkNotExclusivelyAssociatedWithTerminal( removingTN, sink ) ) {
                count++;
                if ( count > 1 ) {
                    // There is more than one sink that is not for the removing rule
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean sinkNotExclusivelyAssociatedWithTerminal( TerminalNode removingTN, LeftTupleSinkNode sink ) {
        return sink.getAssociationsSize() > 1 || !sink.isAssociatedWith( removingTN.getRule() ) ||
               !removingTN.isTerminalNodeOf( sink ) || hasTerminalNodesDifferentThan( sink, removingTN );
    }

    private static boolean hasTerminalNodesDifferentThan(LeftTupleSinkNode node, TerminalNode tn) {
        LeftTupleSinkPropagator sinkPropagator = node.getSinkPropagator();
        for ( LeftTupleSinkNode sink = sinkPropagator.getFirstLeftTupleSink(); sink != null; sink = sink.getNextLeftTupleSinkNode() )  {
            if (sink instanceof TerminalNode) {
                if (tn.getId() != sink.getId()) {
                    return true;
                }
            } else if (hasTerminalNodesDifferentThan(sink, tn)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectTypeNode getQueryOtn(LeftTupleSource lts) {
        while (!(lts instanceof LeftInputAdapterNode)) {
            lts = lts.getLeftTupleSource();
        }

        LeftInputAdapterNode liaNode = (LeftInputAdapterNode) lts;
        ObjectSource os = liaNode.getObjectSource();
        while (!(os instanceof EntryPointNode)) {
            os = os.getParentObjectSource();
        }

        return ((EntryPointNode) os).getQueryNode();
    }

    private static LeftInputAdapterNode getQueryLiaNode(String queryName, ObjectTypeNode queryOtn) {
        for (ObjectSink sink : queryOtn.getObjectSinkPropagator().getSinks()) {
            AlphaNode alphaNode = (AlphaNode) sink;
            QueryNameConstraint nameConstraint = (QueryNameConstraint) alphaNode.getConstraint();
            if (queryName.equals(nameConstraint.getQueryName())) {
                return (LeftInputAdapterNode) alphaNode.getObjectSinkPropagator().getSinks()[0];
            }
        }

        throw new RuntimeException("Unable to find query '" + queryName + "'");
    }

    private static final int NOT_NODE_BIT               = 1 << 0;
    private static final int JOIN_NODE_BIT              = 1 << 1;
    private static final int REACTIVE_EXISTS_NODE_BIT   = 1 << 2;
    private static final int PASSIVE_EXISTS_NODE_BIT    = 1 << 3;

    public static int updateNodeTypesMask(NetworkNode node, int mask) {
        if (node != null) {
            switch ( node.getType() ) {
                case NodeTypeEnums.JoinNode:
                    mask |= JOIN_NODE_BIT;
                    break;
                case NodeTypeEnums.ExistsNode:
                    if ( ( (ExistsNode) node ).isRightInputPassive() ) {
                        mask |= PASSIVE_EXISTS_NODE_BIT;
                    } else {
                        mask |= REACTIVE_EXISTS_NODE_BIT;
                    }
                    break;
                case NodeTypeEnums.NotNode:
                    mask |= NOT_NODE_BIT;
                    break;
            }
        }
        return mask;
    }

    public static boolean isSet(int mask, int bit) {
        return (mask & bit) == bit;
    }
}
