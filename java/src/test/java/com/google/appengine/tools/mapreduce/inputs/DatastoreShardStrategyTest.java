package com.google.appengine.tools.mapreduce.inputs;

import static com.google.appengine.api.datastore.Query.CompositeFilterOperator.AND;
import static com.google.appengine.api.datastore.Query.FilterOperator.GREATER_THAN_OR_EQUAL;
import static com.google.appengine.api.datastore.Query.FilterOperator.LESS_THAN;
import static com.google.appengine.tools.mapreduce.inputs.DatastoreShardStrategy.splitRange;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.CompositeFilter;
import com.google.appengine.api.datastore.Query.Filter;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.api.datastore.Rating;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.appengine.tools.mapreduce.impl.util.SerializationUtil;
import com.google.common.collect.ImmutableList;

import junit.framework.TestCase;

import org.junit.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.SortedSet;

public class DatastoreShardStrategyTest extends TestCase {

  private final int SHARD_COUNT = 5;
  private DatastoreShardStrategy strategy;
  static final String ENTITY_KIND_NAME = "kind";
  static final String PROPERTY_NAME = Entity.KEY_RESERVED_PROPERTY;
  private Query baseQuery;
  private final LocalServiceTestHelper helper = new LocalServiceTestHelper(
      new LocalDatastoreServiceTestConfig().setApplyAllHighRepJobPolicy());
  private DatastoreService ds;

  private static Query createQuery(String kind, String property, Object lowerBound,
      Object upperBound) {
    ImmutableList<Filter> f = ImmutableList.<Filter>builder()
        .add(new FilterPredicate(property, GREATER_THAN_OR_EQUAL, lowerBound))
        .add(new FilterPredicate(property, LESS_THAN, upperBound)).build();
    return BaseDatastoreInput.createQuery(null, kind).setFilter(new CompositeFilter(AND, f));
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    helper.setUp();
    ds = DatastoreServiceFactory.getDatastoreService();
    strategy = new DatastoreShardStrategy(ds);
    baseQuery = BaseDatastoreInput.createQuery(null, ENTITY_KIND_NAME);
  }

  @Override
  public void tearDown() throws Exception {
    helper.tearDown();
    super.tearDown();
  }

  public void testByte() {
    validateSplits(createQuery(ENTITY_KIND_NAME, PROPERTY_NAME, (byte) 0, (byte) 100),
        longRanges(0, 20, 40, 60, 80, 100));
  }

  public void testShort() {
    validateSplits(createQuery(ENTITY_KIND_NAME, PROPERTY_NAME, (short) 0, (short) 100),
        longRanges(0, 20, 40, 60, 80, 100));
  }

  public void testInt() {
    validateSplits(createQuery(ENTITY_KIND_NAME, PROPERTY_NAME, 0, 100),
        longRanges(0, 20, 40, 60, 80, 100));
  }

  public void testLong() {
    validateSplits(createQuery(ENTITY_KIND_NAME, PROPERTY_NAME, 0L, 100L),
        longRanges(0, 20, 40, 60, 80, 100));
  }

  public void testDate() {
    validateSplits(createQuery(ENTITY_KIND_NAME, PROPERTY_NAME, new Date(0), new Date(100)),
        longRanges(0, 20000, 40000, 60000, 80000, 100000));
  }

  public void testRating() {
    validateSplits(createQuery(ENTITY_KIND_NAME, PROPERTY_NAME, new Rating(0), new Rating(100)),
        longRanges(0, 20, 40, 60, 80, 100));
  }

  public void testFindingLowerBound() throws Exception {
    Calendar cal =  Calendar.getInstance();
    Date upperBound = cal.getTime();
    cal.add(Calendar.DATE, -1);
    Date lowerBound = cal.getTime();
    String kind = ENTITY_KIND_NAME + "_FindLowerBound";
    Entity entity = new Entity(kind, "name1");
    entity.setProperty("date", lowerBound);
    ds.put(entity);
    Filter filter = new FilterPredicate("date", LESS_THAN, upperBound);
    Query query = BaseDatastoreInput.createQuery(null, kind).setFilter(filter);
    List<Query> splitQuery = strategy.splitQuery(query, 1);
    assertEquals(1, splitQuery.size());
    CompositeFilter rangeFilter = (CompositeFilter) splitQuery.get(0).getFilter();
    assertEquals(2, rangeFilter.getSubFilters().size());
    for (Filter f : rangeFilter.getSubFilters()) {
      FilterPredicate fp = (FilterPredicate) f;
      switch (fp.getOperator()) {
        case GREATER_THAN_OR_EQUAL:
          assertEquals(lowerBound.getTime() * 1000L, fp.getValue());
          break;
        case LESS_THAN:
          assertEquals(upperBound.getTime() * 1000L, fp.getValue());
          break;
        default:
          fail("Unexpected operator " + fp);
      }
    }
  }

  @Test
  public void testSplitRangeLongs() {
    assertExpectedValues(new long[]{0, 1}, splitRange(0, 1, 2));
    assertExpectedValues(new long[]{0, 1, 2, 3, 4}, splitRange(0, 4, 8));
    assertExpectedValues(new long[]{0, 50, 100}, splitRange(0, 100, 2));
    assertExpectedValues(new long[]{0, 33, 67, 100}, splitRange(0, 100, 3));
  }

  @Test
  public void testSplitRangeDouble() {
    assertExpectedValues(new double[]{0.0, 0.5, 1.0}, splitRange(0D, 1D, 2));
    assertExpectedValues(new double[]{0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0},
        splitRange(0D, 4D, 8));
    assertExpectedValues(new double[]{0.0, 60.0, 120.0}, splitRange(0D, 120D, 2));
    assertExpectedValues(new double[]{0.0, 40.0, 80.0, 120.0}, splitRange(0D, 120D, 3));
  }

  private void assertExpectedValues(long[] expected, SortedSet<Long> observed) {
    String errorMessage = "Expected " + Arrays.toString(expected) + " observed " + observed;
    assertEquals(errorMessage, expected.length, observed.size());
    Long[] array = observed.toArray(new Long[] {});
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], array[i].longValue());
    }
  }

  private void assertExpectedValues(double[] expected, SortedSet<Double> observed) {
    String errorMessage = "Expected " + Arrays.toString(expected) + " observed " + observed;
    assertEquals(errorMessage, expected.length, observed.size());
    Double[] array = observed.toArray(new Double[] {});
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], array[i].doubleValue());
    }
  }

  @SafeVarargs
  private final List<Query> longRanges(long first, long... rest) {
    long previous = first;
    ArrayList<Query> result = new ArrayList<>();
    for (long item : rest) {
      result.add(createQuery(ENTITY_KIND_NAME, PROPERTY_NAME, previous, item));
      previous = item;
    }
    return result;
  }

  private <T extends Serializable & Comparable<T>> void validateSplits(Query orig,
      List<Query> expectedResults) {
    List<Query> results = strategy.splitQuery(orig, SHARD_COUNT);
    assertEquals(expectedResults.size(), results.size());
    int i = 0;
    for (Query result : results) {
      assertEquals(expectedResults.get(i++).getFilter(), result.getFilter());
      assertEquals(SerializationUtil.clone(baseQuery).setFilter(result.getFilter()), result);
    }
  }
}
