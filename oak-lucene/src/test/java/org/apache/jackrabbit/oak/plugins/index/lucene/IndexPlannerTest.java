/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.plugins.index.lucene;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.of;
import static javax.jcr.PropertyType.TYPENAME_STRING;
import static org.apache.jackrabbit.oak.api.Type.NAMES;
import static org.apache.jackrabbit.oak.api.Type.STRINGS;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.DECLARING_NODE_TYPES;
import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NAME;
import static org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexConstants.INDEX_DATA_CHILD_NAME;
import static org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexConstants.INDEX_RULES;
import static org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexConstants.ORDERED_PROP_NAMES;
import static org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexConstants.VERSION;
import static org.apache.jackrabbit.oak.plugins.index.lucene.TestUtil.NT_TEST;
import static org.apache.jackrabbit.oak.plugins.index.lucene.TestUtil.registerTestNodeType;
import static org.apache.jackrabbit.oak.plugins.index.lucene.util.LuceneIndexHelper.newLuceneIndexDefinition;
import static org.apache.jackrabbit.oak.plugins.index.lucene.util.LuceneIndexHelper.newLucenePropertyIndexDefinition;
import static org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState.EMPTY_NODE;
import static org.apache.jackrabbit.oak.plugins.memory.PropertyStates.createProperty;
import static org.apache.jackrabbit.oak.plugins.nodetype.write.InitialContent.INITIAL_CONTENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.plugins.index.IndexConstants;
import org.apache.jackrabbit.oak.plugins.index.lucene.reader.DefaultIndexReader;
import org.apache.jackrabbit.oak.plugins.index.lucene.reader.LuceneIndexReader;
import org.apache.jackrabbit.oak.plugins.index.lucene.reader.LuceneIndexReaderFactory;
import org.apache.jackrabbit.oak.query.NodeStateNodeTypeInfoProvider;
import org.apache.jackrabbit.oak.query.QueryEngineSettings;
import org.apache.jackrabbit.oak.query.ast.NodeTypeInfo;
import org.apache.jackrabbit.oak.query.ast.NodeTypeInfoProvider;
import org.apache.jackrabbit.oak.query.ast.Operator;
import org.apache.jackrabbit.oak.query.ast.SelectorImpl;
import org.apache.jackrabbit.oak.query.fulltext.FullTextAnd;
import org.apache.jackrabbit.oak.query.fulltext.FullTextContains;
import org.apache.jackrabbit.oak.query.fulltext.FullTextExpression;
import org.apache.jackrabbit.oak.query.fulltext.FullTextParser;
import org.apache.jackrabbit.oak.query.index.FilterImpl;
import org.apache.jackrabbit.oak.spi.query.Filter;
import org.apache.jackrabbit.oak.spi.query.PropertyValues;
import org.apache.jackrabbit.oak.spi.query.QueryIndex;
import org.apache.jackrabbit.oak.spi.query.QueryIndex.OrderEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.junit.Test;

import com.google.common.collect.ImmutableList;

public class IndexPlannerTest {
    private NodeState root = INITIAL_CONTENT;

    private NodeBuilder builder = root.builder();

    @Test
    public void planForSortField() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(createProperty(ORDERED_PROP_NAMES, of("foo"), STRINGS));
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        IndexPlanner planner = new IndexPlanner(node, "/foo", createFilter("nt:base"),
                ImmutableList.of(new OrderEntry("foo", Type.LONG, OrderEntry.Order.ASCENDING)));
        assertNotNull(planner.getPlan());
        assertTrue(pr(planner.getPlan()).isUniquePathsRequired());
    }

    @Test
    public void noPlanForSortOnlyByScore() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        IndexPlanner planner = new IndexPlanner(node, "/foo", createFilter("nt:file"),
                ImmutableList.of(new OrderEntry("jcr:score", Type.LONG, OrderEntry.Order.ASCENDING)));
        assertNull(planner.getPlan());
    }

    @Test
    public void fullTextQueryNonFulltextIndex() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:base");
        filter.setFullTextConstraint(FullTextParser.parse(".", "mountain"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNull(planner.getPlan());
    }

    @Test
    public void noApplicableRule() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(createProperty(IndexConstants.DECLARING_NODE_TYPES, of("nt:folder"), STRINGS));
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:base");
        filter.restrictProperty("foo", Operator.EQUAL, PropertyValues.newString("bar"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNull(planner.getPlan());

        filter = createFilter("nt:folder");
        filter.restrictProperty("foo", Operator.EQUAL, PropertyValues.newString("bar"));
        planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNotNull(planner.getPlan());
    }

    @Test
    public void nodeTypeInheritance() throws Exception{
        //Index if for nt:hierarchyNode and query is for nt:folder
        //as nt:folder extends nt:hierarchyNode we should get a plan
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(createProperty(IndexConstants.DECLARING_NODE_TYPES, of("nt:hierarchyNode"), STRINGS));
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:folder");
        filter.restrictProperty("foo", Operator.EQUAL, PropertyValues.newString("bar"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNotNull(planner.getPlan());
    }

    @Test
    public void noMatchingProperty() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:base");
        filter.restrictProperty("bar", Operator.EQUAL, PropertyValues.newString("bar"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNull(planner.getPlan());
    }
    @Test
    public void matchingProperty() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:base");
        filter.restrictProperty("foo", Operator.EQUAL, PropertyValues.newString("bar"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        QueryIndex.IndexPlan plan = planner.getPlan();
        assertNotNull(plan);
        assertNotNull(pr(plan));
        assertTrue(pr(plan).evaluateNonFullTextConstraints());
    }

    @Test
    public void purePropertyIndexAndPathRestriction() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(LuceneIndexConstants.EVALUATE_PATH_RESTRICTION, true);
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:base");
        filter.restrictPath("/content", Filter.PathRestriction.ALL_CHILDREN);
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNull(planner.getPlan());
    }

    @Test
    public void fulltextIndexAndPathRestriction() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(LuceneIndexConstants.EVALUATE_PATH_RESTRICTION, true);

        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_NODE_SCOPE_INDEX, true);

        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:base");
        filter.restrictPath("/content", Filter.PathRestriction.ALL_CHILDREN);
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());

        //For case when a full text property is present then path restriction can be
        //evaluated
        assertNotNull(planner.getPlan());
    }

    @Test
    public void fulltextIndexAndNodeTypeRestriction() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(LuceneIndexConstants.EVALUATE_PATH_RESTRICTION, true);
        defn.setProperty(IndexConstants.DECLARING_NODE_TYPES, of("nt:file"), NAMES);

        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:file/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_NODE_SCOPE_INDEX, true);

        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:file");
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());

        //For case when a full text property is present then path restriction can be
        //evaluated
        assertNotNull(planner.getPlan());
    }

    @Test
    public void pureNodeTypeWithEvaluatePathRestrictionEnabled() throws Exception{
        NodeBuilder index = builder.child(INDEX_DEFINITIONS_NAME);
        NodeBuilder defn = newLuceneIndexDefinition(index, "lucene",
                of(TYPENAME_STRING));
        defn.setProperty(LuceneIndexConstants.EVALUATE_PATH_RESTRICTION, true);
        TestUtil.useV2(defn);

        FilterImpl filter = createFilter("nt:file");
        filter.restrictPath("/", Filter.PathRestriction.ALL_CHILDREN);

        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());

        // /jcr:root//element(*, nt:file)
        //For queries like above Fulltext index should not return a plan
        assertNull(planner.getPlan());
    }

    @Test
    public void purePropertyIndexAndNodeTypeRestriction() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(LuceneIndexConstants.EVALUATE_PATH_RESTRICTION, true);
        defn.setProperty(IndexConstants.DECLARING_NODE_TYPES, of("nt:file"), NAMES);

        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:file");
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());

        assertNull(planner.getPlan());
    }

    @Test
    public void purePropertyIndexAndNodeTypeRestriction2() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(LuceneIndexConstants.EVALUATE_PATH_RESTRICTION, true);

        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_NODE_SCOPE_INDEX, true);

        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:file");
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());

        //No plan should be result for a index with just a rule for nt:base
        assertNull(planner.getPlan());
    }

    @Test
    public void purePropertyIndexAndNodeTypeRestriction3() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(LuceneIndexConstants.EVALUATE_PATH_RESTRICTION, true);
        defn.setProperty(IndexConstants.DECLARING_NODE_TYPES, of("nt:file"), NAMES);

        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:file/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_NODE_SCOPE_INDEX, true);

        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:file");
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());

        QueryIndex.IndexPlan plan = planner.getPlan();
        assertNotNull(plan);
        assertNotNull(pr(plan));
        assertTrue(pr(plan).evaluateNodeTypeRestriction());
    }

    @Test
    public void worksWithIndexFormatV2Onwards() throws Exception{
        NodeBuilder index = builder.child(INDEX_DEFINITIONS_NAME);
        NodeBuilder nb = newLuceneIndexDefinition(index, "lucene",
                of(TYPENAME_STRING));
        //Dummy data node to ensure that IndexDefinition does not consider it
        //as a fresh indexing case
        nb.child(INDEX_DATA_CHILD_NAME);

        IndexNode node = createIndexNode(new IndexDefinition(root, nb.getNodeState()));
        FilterImpl filter = createFilter("nt:base");
        filter.setFullTextConstraint(FullTextParser.parse(".", "mountain"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNull(planner.getPlan());
    }

    @Test
    public void propertyIndexCost() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        long numofDocs = IndexDefinition.DEFAULT_ENTRY_COUNT + 1000;

        IndexDefinition idxDefn = new IndexDefinition(root, defn.getNodeState());
        IndexNode node = createIndexNode(idxDefn, numofDocs);
        FilterImpl filter = createFilter("nt:base");
        filter.restrictProperty("foo", Operator.EQUAL, PropertyValues.newString("bar"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        QueryIndex.IndexPlan plan = planner.getPlan();

        //For propertyIndex if entry count (default to IndexDefinition.DEFAULT_ENTRY_COUNT) is
        //less than numOfDoc then that would be preferred
        assertEquals(idxDefn.getEntryCount(), plan.getEstimatedEntryCount());
        assertEquals(1.0, plan.getCostPerExecution(), 0);
        assertEquals(1.0, plan.getCostPerEntry(), 0);
    }

    @Test
    public void propertyIndexCost2() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(LuceneIndexConstants.COST_PER_ENTRY, 2.0);
        defn.setProperty(LuceneIndexConstants.COST_PER_EXECUTION, 3.0);

        long numofDocs = IndexDefinition.DEFAULT_ENTRY_COUNT - 100;
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()), numofDocs);
        FilterImpl filter = createFilter("nt:base");
        filter.restrictProperty("foo", Operator.EQUAL, PropertyValues.newString("bar"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        QueryIndex.IndexPlan plan = planner.getPlan();

        assertEquals(numofDocs, plan.getEstimatedEntryCount());
        assertEquals(3.0, plan.getCostPerExecution(), 0);
        assertEquals(2.0, plan.getCostPerEntry(), 0);
        assertNotNull(plan);
    }

    @Test
    public void fulltextIndexCost() throws Exception{
        NodeBuilder index = builder.child(INDEX_DEFINITIONS_NAME);
        NodeBuilder defn = newLuceneIndexDefinition(index, "lucene",
                of(TYPENAME_STRING));
        TestUtil.useV2(defn);

        long numofDocs = IndexDefinition.DEFAULT_ENTRY_COUNT + 1000;
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()), numofDocs);
        FilterImpl filter = createFilter("nt:base");
        filter.setFullTextConstraint(FullTextParser.parse(".", "mountain"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());

        QueryIndex.IndexPlan plan = planner.getPlan();
        assertNotNull(plan);
        assertEquals(numofDocs, plan.getEstimatedEntryCount());
    }

    @Test
    public void nullPropertyCheck() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");

        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:base");
        filter.restrictProperty("foo", Operator.EQUAL, null);
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        QueryIndex.IndexPlan plan = planner.getPlan();
        assertNull("For null checks no plan should be returned", plan);
    }

    @Test
    public void nullPropertyCheck2() throws Exception{
        root = registerTestNodeType(builder).getNodeState();
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        NodeBuilder rules = defn.child(INDEX_RULES);
        TestUtil.child(rules, "oak:TestNode/properties/prop2")
                .setProperty(LuceneIndexConstants.PROP_NAME, "foo")
                .setProperty(LuceneIndexConstants.PROP_NULL_CHECK_ENABLED, true)
                .setProperty(LuceneIndexConstants.PROP_PROPERTY_INDEX, true);

        IndexDefinition idxDefn = new IndexDefinition(root, builder.getNodeState().getChildNode("test"));
        IndexNode node = createIndexNode(idxDefn);

        FilterImpl filter = createFilter(NT_TEST);
        filter.restrictProperty("foo", Operator.EQUAL, null);

        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        QueryIndex.IndexPlan plan = planner.getPlan();
        assertNotNull("For null checks plan should be returned with nullCheckEnabled", plan);
        IndexPlanner.PlanResult pr =
                (IndexPlanner.PlanResult) plan.getAttribute(LucenePropertyIndex.ATTR_PLAN_RESULT);
        assertNotNull(pr.getPropDefn(filter.getPropertyRestriction("foo")));
    }

    @Test
    public void noPathRestHasQueryPath() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(createProperty(IndexConstants.QUERY_PATHS, of("/test/a"), Type.STRINGS));
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));

        FilterImpl filter = createFilter("nt:base");
        filter.restrictProperty("foo", Operator.EQUAL, PropertyValues.newString("bar"));
        filter.restrictPath("/test2", Filter.PathRestriction.ALL_CHILDREN);
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNull(planner.getPlan());
    }

    @Test
    public void hasPathRestHasMatchingQueryPaths() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(createProperty(IndexConstants.QUERY_PATHS, of("/test/a", "/test/b"), Type.STRINGS));
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));

        FilterImpl filter = createFilter("nt:base");
        filter.restrictPath("/test/a", Filter.PathRestriction.ALL_CHILDREN);
        filter.restrictProperty("foo", Operator.EQUAL, PropertyValues.newString("bar"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNotNull(planner.getPlan());
    }

    @Test
    public void hasPathRestHasNoExplicitQueryPaths() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));

        FilterImpl filter = createFilter("nt:base");
        filter.restrictPath("/test2", Filter.PathRestriction.ALL_CHILDREN);
        filter.restrictProperty("foo", Operator.EQUAL, PropertyValues.newString("bar"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
        assertNotNull(planner.getPlan());
    }

    @Test
    public void noPlanForFulltextQueryAndOnlyAnalyzedProperties() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(LuceneIndexConstants.EVALUATE_PATH_RESTRICTION, true);

        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);

        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:base");
        filter.setFullTextConstraint(FullTextParser.parse(".", "mountain"));
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());

        QueryIndex.IndexPlan plan = planner.getPlan();
        assertNull(plan);
    }

    @Test
    public void noPlanForNodeTypeQueryAndOnlyAnalyzedProperties() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(LuceneIndexConstants.EVALUATE_PATH_RESTRICTION, true);
        defn.setProperty(IndexConstants.DECLARING_NODE_TYPES, of("nt:file"), NAMES);

        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:file/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);

        IndexNode node = createIndexNode(new IndexDefinition(root, defn.getNodeState()));
        FilterImpl filter = createFilter("nt:file");
        filter.restrictPath("/foo", Filter.PathRestriction.ALL_CHILDREN);
        IndexPlanner planner = new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());

        QueryIndex.IndexPlan plan = planner.getPlan();
        assertNull(plan);
    }

    //------ Suggestion/spellcheck plan tests
    @Test
    public void nonSuggestIndex() throws Exception {
        //An index which doesn't define any property to support suggestions shouldn't turn up in plan.
        String indexNodeType = "nt:base";
        String queryNodeType = "nt:base";
        boolean enableSuggestionIndex = false;
        boolean enableSpellcheckIndex = false;
        boolean queryForSugggestion = true;

        IndexNode node = createSuggestionOrSpellcheckIndex(indexNodeType, enableSuggestionIndex, enableSpellcheckIndex);
        QueryIndex.IndexPlan plan = getSuggestOrSpellcheckIndexPlan(node, queryNodeType, queryForSugggestion);

        assertNull(plan);
    }

    @Test
    public void nonSpellcheckIndex() throws Exception {
        //An index which doesn't define any property to support spell check shouldn't turn up in plan.
        String indexNodeType = "nt:base";
        String queryNodeType = "nt:base";
        boolean enableSuggestionIndex = false;
        boolean enableSpellcheckIndex = false;
        boolean queryForSugggestion = false;

        IndexNode node = createSuggestionOrSpellcheckIndex(indexNodeType, enableSuggestionIndex, enableSpellcheckIndex);
        QueryIndex.IndexPlan plan = getSuggestOrSpellcheckIndexPlan(node, queryNodeType, queryForSugggestion);

        assertNull(plan);
    }

    @Test
    public void simpleSuggestIndexPlan() throws Exception {
        //An index defining a property for suggestions should turn up in plan.
        String indexNodeType = "nt:base";
        String queryNodeType = "nt:base";
        boolean enableSuggestionIndex = true;
        boolean enableSpellcheckIndex = false;
        boolean queryForSugggestion = true;

        IndexNode node = createSuggestionOrSpellcheckIndex(indexNodeType, enableSuggestionIndex, enableSpellcheckIndex);
        QueryIndex.IndexPlan plan = getSuggestOrSpellcheckIndexPlan(node, queryNodeType, queryForSugggestion);

        assertNotNull(plan);
        assertFalse(pr(plan).isUniquePathsRequired());
    }

    @Test
    public void simpleSpellcheckIndexPlan() throws Exception {
        //An index defining a property for spellcheck should turn up in plan.
        String indexNodeType = "nt:base";
        String queryNodeType = "nt:base";
        boolean enableSuggestionIndex = false;
        boolean enableSpellcheckIndex = true;
        boolean queryForSugggestion = false;

        IndexNode node = createSuggestionOrSpellcheckIndex(indexNodeType, enableSuggestionIndex, enableSpellcheckIndex);
        QueryIndex.IndexPlan plan = getSuggestOrSpellcheckIndexPlan(node, queryNodeType, queryForSugggestion);

        assertNotNull(plan);
        assertFalse(pr(plan).isUniquePathsRequired());
    }

    @Test
    public void suggestionIndexingRuleHierarchy() throws Exception {
        //An index defining a property for suggestion on a base type shouldn't turn up in plan.
        String indexNodeType = "nt:base";
        String queryNodeType = "nt:unstructured";
        boolean enableSuggestionIndex = true;
        boolean enableSpellcheckIndex = false;
        boolean queryForSugggestion = true;

        IndexNode node = createSuggestionOrSpellcheckIndex(indexNodeType, enableSuggestionIndex, enableSpellcheckIndex);
        QueryIndex.IndexPlan plan = getSuggestOrSpellcheckIndexPlan(node, queryNodeType, queryForSugggestion);

        assertNull(plan);
    }

    @Test
    public void spellcheckIndexingRuleHierarchy() throws Exception {
        //An index defining a property for spellcheck on a base type shouldn't turn up in plan.
        String indexNodeType = "nt:base";
        String queryNodeType = "nt:unstructured";
        boolean enableSuggestionIndex = false;
        boolean enableSpellcheckIndex = true;
        boolean queryForSugggestion = false;

        IndexNode node = createSuggestionOrSpellcheckIndex(indexNodeType, enableSuggestionIndex, enableSpellcheckIndex);
        QueryIndex.IndexPlan plan = getSuggestOrSpellcheckIndexPlan(node, queryNodeType, queryForSugggestion);

        assertNull(plan);
    }

    @Test
    public void fullTextQuery_RelativePath1() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");

        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);

        IndexPlanner planner = createPlannerForFulltext(defn.getNodeState(), FullTextParser.parse("bar", "mountain"));

        //No plan for unindex property
        assertNull(planner.getPlan());
    }

    @Test
    public void fullTextQuery_IndexAllProps() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("allProps"), "async");

        //Index all props and then perform fulltext
        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/allProps");
        foob.setProperty(LuceneIndexConstants.PROP_NAME, LuceneIndexConstants.REGEX_ALL_PROPS);
        foob.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);
        foob.setProperty(LuceneIndexConstants.PROP_IS_REGEX, true);

        FullTextExpression exp = FullTextParser.parse("bar", "mountain OR valley");
        exp = new FullTextContains("bar", "mountain OR valley", exp);
        IndexPlanner planner = createPlannerForFulltext(defn.getNodeState(), exp);

        //No plan for unindex property
        assertNotNull(planner.getPlan());
    }

    @Test
    public void fullTextQuery_IndexAllProps_NodePathQuery() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("allProps"), "async");

        //Index all props and then perform fulltext
        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/allProps");
        foob.setProperty(LuceneIndexConstants.PROP_NAME, LuceneIndexConstants.REGEX_ALL_PROPS);
        foob.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);
        foob.setProperty(LuceneIndexConstants.PROP_NODE_SCOPE_INDEX, true);
        foob.setProperty(LuceneIndexConstants.PROP_IS_REGEX, true);

        //where contains('jcr:content/*', 'mountain OR valley') can be evaluated by index
        //on nt:base by evaluating on '.' and then checking if node name is 'jcr:content'
        IndexPlanner planner = createPlannerForFulltext(defn.getNodeState(),
                FullTextParser.parse("jcr:content/*", "mountain OR valley"));

        //No plan for unindex property
        assertNotNull(planner.getPlan());
    }

    @Test
    public void fullTextQuery_IndexAllProps_AggregatedNodePathQuery() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("allProps"), "async");

        //Index all props and then perform fulltext
        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder agg = defn.child(LuceneIndexConstants.AGGREGATES).child("nt:base").child("include0");
        agg.setProperty(LuceneIndexConstants.AGG_PATH, "jcr:content");
        agg.setProperty(LuceneIndexConstants.AGG_RELATIVE_NODE, true);

        //where contains('jcr:content/*', 'mountain OR valley') can be evaluated by index
        //on nt:base by evaluating on '.' and then checking if node name is 'jcr:content'
        IndexPlanner planner = createPlannerForFulltext(defn.getNodeState(),
                FullTextParser.parse("jcr:content/*", "mountain OR valley"));

        //No plan for unindex property
        assertNotNull(planner.getPlan());
    }

    @Test
    public void fullTextQuery_IndexAllProps_NodePathQuery_NoPlan() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");

        //Index all props and then perform fulltext
        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_NAME, "foo");
        foob.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);

        //where contains('jcr:content/*', 'mountain OR valley') can be evaluated by index
        //on nt:base by evaluating on '.' and then checking if node name is 'jcr:content'
        IndexPlanner planner = createPlannerForFulltext(defn.getNodeState(),
                FullTextParser.parse("jcr:content/*", "mountain OR valley"));

        //No plan for unindex property
        assertNull(planner.getPlan());
    }

    @Test
    public void fullTextQuery_NonAnalyzedProp_NoPlan() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo", "bar"), "async");

        //Index all props and then perform fulltext
        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_NAME, "foo");

        NodeBuilder barb = getNode(defn, "indexRules/nt:base/properties/bar");
        barb.setProperty(LuceneIndexConstants.PROP_NAME, "bar");
        barb.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);

        //where contains('jcr:content/*', 'mountain OR valley') can be evaluated by index
        //on nt:base by evaluating on '.' and then checking if node name is 'jcr:content'
        IndexPlanner planner = createPlannerForFulltext(defn.getNodeState(),
                FullTextParser.parse("foo", "mountain OR valley"));

        //No plan for unindex property
        assertNull(planner.getPlan());
    }

    @Test
    public void fullTextQuery_RelativePropertyPaths() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo", "bar"), "async");

        //Index all props and then perform fulltext
        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_NAME, "foo");
        foob.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);

        NodeBuilder barb = getNode(defn, "indexRules/nt:base/properties/bar");
        barb.setProperty(LuceneIndexConstants.PROP_NAME, "bar");
        barb.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);


        //where contains('jcr:content/bar', 'mountain OR valley') and contains('jcr:content/foo', 'mountain OR valley')
        //above query can be evaluated by index which indexes foo and bar with restriction that both belong to same node
        //by displacing the query path to evaluate on contains('bar', ...) and filter out those parents which do not
        //have jcr:content as parent
        FullTextExpression fooExp = FullTextParser.parse("jcr:content/bar", "mountain OR valley");
        FullTextExpression barExp = FullTextParser.parse("jcr:content/foo", "mountain OR valley");
        FullTextExpression exp = new FullTextAnd(Arrays.asList(fooExp, barExp));
        IndexPlanner planner = createPlannerForFulltext(defn.getNodeState(),exp);

        //No plan for unindex property
        assertNotNull(planner.getPlan());
    }

    @Test
    public void fullTextQuery_DisjointPropertyPaths() throws Exception{
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo", "bar"), "async");

        //Index all props and then perform fulltext
        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/nt:base/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_NAME, "foo");
        foob.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);

        NodeBuilder barb = getNode(defn, "indexRules/nt:base/properties/bar");
        barb.setProperty(LuceneIndexConstants.PROP_NAME, "bar");
        barb.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);

        FullTextExpression fooExp = FullTextParser.parse("metadata/bar", "mountain OR valley");
        FullTextExpression barExp = FullTextParser.parse("jcr:content/foo", "mountain OR valley");
        FullTextExpression exp = new FullTextAnd(Arrays.asList(fooExp, barExp));
        IndexPlanner planner = createPlannerForFulltext(defn.getNodeState(),exp);

        //No plan for unindex property
        assertNull(planner.getPlan());
    }

    private IndexPlanner createPlannerForFulltext(NodeState defn, FullTextExpression exp) throws IOException {
        IndexNode node = createIndexNode(new IndexDefinition(root, defn));
        FilterImpl filter = createFilter("nt:base");
        filter.setFullTextConstraint(exp);
        return new IndexPlanner(node, "/foo", filter, Collections.<OrderEntry>emptyList());
    }

    private IndexNode createSuggestionOrSpellcheckIndex(String nodeType,
                                                        boolean enableSuggestion,
                                                        boolean enableSpellcheck) throws Exception {
        NodeBuilder defn = newLucenePropertyIndexDefinition(builder, "test", of("foo"), "async");
        defn.setProperty(DECLARING_NODE_TYPES, nodeType);

        defn = IndexDefinition.updateDefinition(defn.getNodeState().builder());
        NodeBuilder foob = getNode(defn, "indexRules/" + nodeType + "/properties/foo");
        foob.setProperty(LuceneIndexConstants.PROP_ANALYZED, true);
        if (enableSuggestion) {
            foob.setProperty(LuceneIndexConstants.PROP_USE_IN_SUGGEST, true);
        } if (enableSpellcheck) {
            foob.setProperty(LuceneIndexConstants.PROP_USE_IN_SPELLCHECK, true);
        }

        IndexDefinition indexDefinition = new IndexDefinition(root, defn.getNodeState());
        return createIndexNode(indexDefinition);
    }

    private QueryIndex.IndexPlan getSuggestOrSpellcheckIndexPlan(IndexNode indexNode, String nodeType,
                                                                 boolean forSugggestion) throws Exception {
        FilterImpl filter = createFilter(nodeType);
        filter.restrictProperty(indexNode.getDefinition().getFunctionName(), Operator.EQUAL,
                PropertyValues.newString((forSugggestion?"suggest":"spellcheck") + "?term=foo"));
        IndexPlanner planner = new IndexPlanner(indexNode, "/foo", filter, Collections.<OrderEntry>emptyList());

        return planner.getPlan();
    }
    //------ END - Suggestion/spellcheck plan tests

    private IndexNode createIndexNode(IndexDefinition defn, long numOfDocs) throws IOException {
        return new IndexNode("foo", defn, new TestReaderFactory(createSampleDirectory(numOfDocs)).createReaders(defn, EMPTY_NODE, "foo"));
    }

    private IndexNode createIndexNode(IndexDefinition defn) throws IOException {
        return new IndexNode("foo", defn, new TestReaderFactory(createSampleDirectory()).createReaders(defn, EMPTY_NODE, "foo"));
    }

    private FilterImpl createFilter(String nodeTypeName) {
        NodeTypeInfoProvider nodeTypes = new NodeStateNodeTypeInfoProvider(root);
        NodeTypeInfo type = nodeTypes.getNodeTypeInfo(nodeTypeName);
        SelectorImpl selector = new SelectorImpl(type, nodeTypeName);
        return new FilterImpl(selector, "SELECT * FROM [" + nodeTypeName + "]", new QueryEngineSettings());
    }

    private static Directory createSampleDirectory() throws IOException {
        return createSampleDirectory(1);
    }

    private static Directory createSampleDirectory(long numOfDocs) throws IOException {
        Directory dir = new RAMDirectory();
        IndexWriterConfig config = new IndexWriterConfig(VERSION, LuceneIndexConstants.ANALYZER);
        IndexWriter writer = new  IndexWriter(dir, config);
        for (int i = 0; i < numOfDocs; i++) {
            Document doc = new Document();
            doc.add(new StringField("foo", "bar" + i, Field.Store.NO));
            writer.addDocument(doc);
        }
        writer.close();
        return dir;
    }

    private static IndexPlanner.PlanResult pr(QueryIndex.IndexPlan plan) {
        return (IndexPlanner.PlanResult) plan.getAttribute(LucenePropertyIndex.ATTR_PLAN_RESULT);
    }

    @Nonnull
    private static NodeBuilder getNode(@Nonnull NodeBuilder node, @Nonnull String path) {
        for (String name : PathUtils.elements(checkNotNull(path))) {
            node = node.getChildNode(checkNotNull(name));
        }
        return node;
    }

    private static class TestReaderFactory implements LuceneIndexReaderFactory {
        final Directory directory;

        private TestReaderFactory(Directory directory) {
            this.directory = directory;
        }

        @Override
        public List<LuceneIndexReader> createReaders(IndexDefinition definition, NodeState definitionState,
                                                     String indexPath) throws IOException {
            List<LuceneIndexReader> readers = new ArrayList<>();
            readers.add(new DefaultIndexReader(directory, null, definition.getAnalyzer()));
            return readers;
        }
    }


}
