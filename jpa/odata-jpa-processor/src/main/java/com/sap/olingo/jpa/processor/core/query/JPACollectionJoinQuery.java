package com.sap.olingo.jpa.processor.core.query;

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Selection;

import org.apache.olingo.commons.api.ex.ODataException;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriInfoResource;
import org.apache.olingo.server.api.uri.queryoption.SelectItem;
import org.apache.olingo.server.api.uri.queryoption.SelectOption;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;

import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAAssociationPath;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAAttribute;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAElement;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAEntityType;
import com.sap.olingo.jpa.metadata.core.edm.mapper.api.JPAPath;
import com.sap.olingo.jpa.metadata.core.edm.mapper.exception.ODataJPAModelException;
import com.sap.olingo.jpa.processor.core.api.JPAODataCRUDContextAccess;
import com.sap.olingo.jpa.processor.core.api.JPAODataRequestContextAccess;
import com.sap.olingo.jpa.processor.core.exception.ODataJPAQueryException;
import com.sap.olingo.jpa.processor.core.exception.ODataJPAQueryException.MessageKeys;

public class JPACollectionJoinQuery extends JPAAbstractJoinQuery {
  private final JPAAssociationPath assoziation;
  private final Optional<JPAKeyBoundary> keyBoundary;

  public JPACollectionJoinQuery(final OData odata, final JPAODataCRUDContextAccess context, final EntityManager em,
      final JPACollectionItemInfo item, final Map<String, List<String>> requestHeaders,
      final JPAODataRequestContextAccess requestContext, final Optional<JPAKeyBoundary> keyBoundary)
      throws ODataException {

    super(odata, context, item.getEntityType(), requestContext,
        requestHeaders, new ArrayList<>(item.getHops().subList(0, item.getHops().size() - 1)));
    this.assoziation = item.getExpandAssociation();
    this.keyBoundary = keyBoundary;
  }

  @Override
  public JPACollectionQueryResult execute() throws ODataApplicationException {
    final int handle = debugger.startRuntimeMeasurement(this, "executeStandradQuery");
    try {
      final TypedQuery<Tuple> tupleQuery = createTupleQuery();
      final int resultHandle = debugger.startRuntimeMeasurement(tupleQuery, "getResultList");
      final List<Tuple> intermediateResult = tupleQuery.getResultList();
      debugger.stopRuntimeMeasurement(resultHandle);

      final Map<String, List<Tuple>> result = convertResult(intermediateResult, assoziation, 0, Long.MAX_VALUE);

      try {
        final Set<JPAPath> requestedSelection = new HashSet<>();
        buildSelectionAddNavigationAndSelect(uriResource, requestedSelection, uriResource.getSelectOption());
        debugger.stopRuntimeMeasurement(handle);
        return new JPACollectionQueryResult(result, new HashMap<String, Long>(1), jpaEntity, this.assoziation,
            requestedSelection);
      } catch (final ODataJPAModelException e) {
        throw new ODataApplicationException(e.getLocalizedMessage(), HttpStatusCode.INTERNAL_SERVER_ERROR
            .getStatusCode(), ODataJPAModelException.getLocales().nextElement(), e);
      }
    } catch (final JPANoSelectionException e) {
      return new JPACollectionQueryResult(this.jpaEntity, assoziation, Collections.emptyList());
    }
  }

  @Override
  protected Set<JPAPath> buildSelectionPathList(final UriInfoResource uriResource) throws ODataApplicationException {
    final Set<JPAPath> jpaPathList = new HashSet<>();
    final String pathPrefix = "";
    final SelectOption select = uriResource.getSelectOption();
    // Following situations have to be handled:
    // - .../Organizations --> Select all collection attributes
    // - .../Organizations('1')/Comment --> Select navigation target
    // - .../Et/St/St --> Select navigation target --> Select navigation target via complex properties
    // - .../Organizations?$select=ID,Comment --> Select collection attributes given by select clause
    // - .../Persons('99')/InhousAddress?$select=Building --> Select attributes of complex collection given by select
    // clause
    try {
      if (SelectOptionUtil.selectAll(select))
        // If the collection is part of a navigation take all the attributes
        expandPath(jpaEntity, jpaPathList, pathPrefix.isEmpty() ? this.assoziation.getAlias() : pathPrefix
            + JPAPath.PATH_SEPERATOR + this.assoziation.getAlias(), true);
      else {
        for (final SelectItem sItem : select.getSelectItems()) {
          final JPAPath selectItemPath = selectItemAsPath(pathPrefix, sItem);
          if (pathContainsCollection(selectItemPath)) {
            if (selectItemPath.getLeaf().isComplex()) {
              final JPAAttribute attribute = selectItemPath.getLeaf();
              expandPath(jpaEntity, jpaPathList, pathPrefix.isEmpty() ? attribute.getExternalName() : pathPrefix
                  + JPAPath.PATH_SEPERATOR + attribute.getExternalName(), true);
            } else {
              jpaPathList.add(selectItemPath);
            }
          } else if (selectItemPath.getLeaf().isComplex()) {
            expandPath(jpaEntity, jpaPathList, pathPrefix.isEmpty() ? this.assoziation.getAlias() : pathPrefix
                + JPAPath.PATH_SEPERATOR + this.assoziation.getAlias(), true);
          }
        }
      }
    } catch (final ODataJPAModelException e) {
      throw new ODataJPAQueryException(MessageKeys.QUERY_PREPARATION_INVALID_SELECTION_PATH,
          HttpStatusCode.BAD_REQUEST);
    }
    return jpaPathList;
  }

  private JPAPath selectItemAsPath(final String pathPrefix, final SelectItem sItem) throws ODataJPAModelException,
      ODataJPAQueryException {

    String pathItem = sItem.getResourcePath().getUriResourceParts().stream().map(path -> (path
        .getSegmentValue())).collect(Collectors.joining(JPAPath.PATH_SEPERATOR));
    pathItem = pathPrefix == null || pathPrefix.isEmpty() ? pathItem : pathPrefix + JPAPath.PATH_SEPERATOR
        + pathItem;
    final JPAPath selectItemPath = jpaEntity.getPath(pathItem);
    if (selectItemPath == null)
      throw new ODataJPAQueryException(MessageKeys.QUERY_PREPARATION_INVALID_SELECTION_PATH,
          HttpStatusCode.BAD_REQUEST);
    return selectItemPath;
  }

  @Override
  protected void expandPath(final JPAEntityType jpaEntity, final Collection<JPAPath> jpaPathList,
      final String selectItem, final boolean targetIsCollection) throws ODataJPAModelException, ODataJPAQueryException {

    final JPAPath selectItemPath = jpaEntity.getPath(selectItem);
    if (selectItemPath == null)

      throw new ODataJPAQueryException(MessageKeys.QUERY_PREPARATION_INVALID_SELECTION_PATH,
          HttpStatusCode.BAD_REQUEST);
    if (selectItemPath.getLeaf().isComplex()) {
      // Complex Type
      final List<JPAPath> p = jpaEntity.searchChildPath(selectItemPath);
      jpaPathList.addAll(p);
    } else {
      // Primitive Type
      jpaPathList.add(selectItemPath);
    }
  }

  @Override
  protected List<Selection<?>> createSelectClause(final Map<String, From<?, ?>> joinTables, // NOSONAR
      final Collection<JPAPath> jpaPathList, final From<?, ?> target, final List<String> groups)
      throws ODataApplicationException { // NOSONAR Allow
    // subclasses to throw an exception

    final int handle = debugger.startRuntimeMeasurement(this, "createSelectClause");
    final List<Selection<?>> selections = new ArrayList<>();
    // Based on an error in Eclipse Link first the join columns have to be selected. Otherwise the alias is assigned to
    // the wrong column. E.g. if Organization Comment shall be read Eclipse Link automatically selects also the Order
    // column and if the join column is added later the select clause would look as follows: SELECT t0."Text,
    // t0."Order", t1,"ID". Eclipse Link will then return the value of the Order column for the alias of the ID column.
    createAdditionSelctionForJoinTable(selections);

    // Build select clause
    for (final JPAPath jpaPath : jpaPathList) {
      if (jpaPath.isPartOfGroups(groups)) {
        final Path<?> p = ExpressionUtil.convertToCriteriaPath(joinTables, target, jpaPath.getPath());
        p.alias(jpaPath.getAlias());
        selections.add(p);
      }
    }

    debugger.stopRuntimeMeasurement(handle);
    return selections;
  }

  /**
   * Splits up a expand results, so it is returned as a map that uses a concatenation of the field values know by the
   * parent.
   * @param intermediateResult
   * @param associationPath
   * @param skip
   * @param top
   * @return
   * @throws ODataApplicationException
   */
  Map<String, List<Tuple>> convertResult(final List<Tuple> intermediateResult, final JPAAssociationPath associationPath,
      final long skip, final long top) throws ODataApplicationException {
    String joinKey = "";
    long skiped = 0;
    long taken = 0;

    List<Tuple> subResult = null;
    final Map<String, List<Tuple>> convertedResult = new HashMap<>();
    for (final Tuple row : intermediateResult) {
      String actuallKey;
      try {
        actuallKey = buildConcatenatedKey(row, associationPath);
      } catch (final ODataJPAModelException e) {
        throw new ODataJPAQueryException(e, HttpStatusCode.BAD_REQUEST);
      }

      if (!actuallKey.equals(joinKey)) {
        subResult = new ArrayList<>();
        convertedResult.put(actuallKey, subResult);
        joinKey = actuallKey;
        skiped = taken = 0;
      }
      if (skiped >= skip && taken < top) {
        taken += 1;
        subResult.add(row);
      } else {
        skiped += 1;
      }
    }
    return convertedResult;
  }

  private String buildConcatenatedKey(final Tuple row, final JPAAssociationPath associationPath)
      throws ODataJPAModelException {

    if (associationPath.getJoinTable() == null) {
      final List<JPAPath> joinColumns = associationPath.getRightColumnsList();
      return joinColumns.stream()
          .map(c -> (row.get(c.getAlias())).toString())
          .collect(joining(JPAPath.PATH_SEPERATOR));
    } else {
      final List<JPAPath> joinColumns = associationPath.getLeftColumnsList();
      return joinColumns.stream()
          .map(c -> (row.get(assoziation.getAlias() + ALIAS_SEPERATOR + c.getAlias())).toString())
          .collect(joining(JPAPath.PATH_SEPERATOR));
    }
  }

  private List<Order> createOrderByJoinCondition(final JPAAssociationPath associationPath)
      throws ODataApplicationException {
    final List<Order> orders = new ArrayList<>();

    try {
      final List<JPAPath> joinColumns = associationPath.getJoinTable() == null
          ? associationPath.getRightColumnsList() : associationPath.getLeftColumnsList();
      final From<?, ?> from = associationPath.getJoinTable() == null
          ? target : determineParentFrom();

      for (final JPAPath j : joinColumns) {
        Path<?> jpaProperty = from;
        for (final JPAElement pathElement : j.getPath()) {
          jpaProperty = jpaProperty.get(pathElement.getInternalName());
        }
        orders.add(cb.asc(jpaProperty));
      }
    } catch (final ODataJPAModelException e) {
      throw new ODataJPAQueryException(e, HttpStatusCode.BAD_REQUEST);
    }
    return orders;
  }

  private TypedQuery<Tuple> createTupleQuery() throws ODataApplicationException, JPANoSelectionException {
    final int handle = debugger.startRuntimeMeasurement(this, "createTupleQuery");

    final Collection<JPAPath> selectionPath = buildSelectionPathList(this.uriResource);
    final Map<String, From<?, ?>> joinTables = createFromClause(new ArrayList<>(1), selectionPath,
        cq, lastInfo);
    // TODO handle Join Column is ignored
    cq.multiselect(createSelectClause(joinTables, selectionPath, target, groups));
    cq.distinct(true);
    final javax.persistence.criteria.Expression<Boolean> whereClause = createWhere();
    if (whereClause != null)
      cq.where(whereClause);

    final List<Order> orderBy = createOrderByJoinCondition(assoziation);
    orderBy.addAll(new JPAOrderByBuilder(jpaEntity, target, cb, groups).createOrderByList(joinTables));
    cq.orderBy(orderBy);

    final TypedQuery<Tuple> query = em.createQuery(cq);
    debugger.stopRuntimeMeasurement(handle);
    return query;
  }

  private Expression<Boolean> createWhere() throws ODataApplicationException {

    final int handle = debugger.startRuntimeMeasurement(this, "createWhere");

    javax.persistence.criteria.Expression<Boolean> whereCondition = null;
    // Given keys: Organizations('1')/Roles(...)
    try {
      whereCondition = createKeyWhere(navigationInfo);
      whereCondition = addWhereClause(whereCondition, createBoundary(navigationInfo, keyBoundary));
    } catch (final ODataApplicationException e) {
      debugger.stopRuntimeMeasurement(handle);
      throw e;
    }

    for (final JPANavigationProptertyInfo info : this.navigationInfo) {
      if (info.getFilterCompiler() != null) {
        try {
          whereCondition = addWhereClause(whereCondition, info.getFilterCompiler().compile());
        } catch (final ExpressionVisitException e) {
          debugger.stopRuntimeMeasurement(handle);
          throw new ODataJPAQueryException(ODataJPAQueryException.MessageKeys.QUERY_PREPARATION_FILTER_ERROR,
              HttpStatusCode.BAD_REQUEST, e);
        }
      }
    }
    debugger.stopRuntimeMeasurement(handle);
    return whereCondition;
  }

  private From<?, ?> determineParentFrom() throws ODataJPAQueryException {
    for (final JPANavigationProptertyInfo item : this.navigationInfo) {
      if (item.getAssociationPath() == assoziation)
        return item.getFromClause();
    }
    throw new ODataJPAQueryException(ODataJPAQueryException.MessageKeys.QUERY_PREPARATION_FILTER_ERROR,
        HttpStatusCode.BAD_REQUEST);
  }

  private void createAdditionSelctionForJoinTable(final List<Selection<?>> selections) throws ODataJPAQueryException {
    final From<?, ?> parent = determineParentFrom(); // e.g. JoinSource
    try {
      for (final JPAPath p : assoziation.getLeftColumnsList()) {
        final Path<?> selection = ExpressionUtil.convertToCriteriaPath(parent, p.getPath());
        // If source and target of an association use the same name for their key we get conflicts with the alias.
        // Therefore it is necessary to unify them.
        selection.alias(assoziation.getAlias() + ALIAS_SEPERATOR + p.getAlias());
        selections.add(selection);
      }
    } catch (final ODataJPAModelException e) {
      throw new ODataJPAQueryException(e, HttpStatusCode.INTERNAL_SERVER_ERROR);
    }
  }

  private boolean pathContainsCollection(final JPAPath p) {
    for (final JPAElement pathElement : p.getPath()) {
      if (pathElement instanceof JPAAttribute && ((JPAAttribute) pathElement).isCollection()) {
        return true;
      }
    }
    return false;
  }
}
