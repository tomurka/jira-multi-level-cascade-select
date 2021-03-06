package com.sourcesense.jira.customfield.searcher;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.JiraDataType;
import com.atlassian.jira.JiraDataTypes;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.impl.CascadingSelectCFType;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.search.managers.SearchHandlerManager;
import com.atlassian.jira.jql.operand.QueryLiteral;
import com.atlassian.jira.jql.query.QueryCreationContext;
import com.atlassian.jira.jql.util.JqlCascadingSelectLiteralUtil;
import com.atlassian.jira.jql.util.JqlSelectOptionsUtil;
import com.atlassian.jira.plugin.jql.function.AbstractJqlFunction;
import com.atlassian.jira.plugin.jql.function.JqlFunction;
import com.atlassian.jira.util.MessageSet;
import com.atlassian.jira.util.MessageSetImpl;
import com.atlassian.query.clause.TerminalClause;
import com.atlassian.query.operand.FunctionOperand;
import com.atlassian.query.operand.Operand;
import com.google.common.base.Predicate;
import com.google.common.collect.Sets;
import com.sourcesense.jira.customfield.type.MultiLevelCascadingSelectCFType;
import org.apache.log4j.Logger;

import java.util.*;

import static com.atlassian.jira.util.dbc.Assertions.notNull;

/**
 * <p/>
 * A function that allows the user to select children of a specific multi-level select Field.
 * <p/>
 * <p/>
 * Unlike most functions, this function requires knowledge of the field it was used with, so that it
 * can figure out which options are valid for it to generate.
 * <p/>
 * <p/>
 * The function accepts either 1 or n arguments:
 * <ul>
 * <li>One argument (parent) - returns all option ids for the specified option's child options.
 * <li>Two arguments (parent, child1,child2...childn) - returns all option ids for the specified
 * parent/child options combination.
 * <li>Two arguments (parent, "None") - returns all option ids for the specified parent option, and
 * the negative option ids for the children of the parents.
 * </ul>
 *
 * @author Alessandro Benedetti
 * @since v4.0
 */
public class MultiLevelCascadeOptionFunction extends AbstractJqlFunction implements JqlFunction {
    private final Logger log = Logger.getLogger(MultiLevelCascadeOptionFunction.class);
    /**
     * For building clauses containing this function
     */
    public static final String FUNCTION_CASCADE_OPTION = "multilevelcascadeOption";

    public static String EMPTY_VALUE = "_none_";

    public static String QUOTED_EMPTY_VALUE = "\"none\"";

    private final JqlSelectOptionsUtil jqlSelectOptionsUtil;

    private final SearchHandlerManager searchHandlerManager;

    private final CustomFieldManager customFieldManager;

    private final JqlCascadingSelectLiteralUtil jqlCascadingSelectLiteralUtil;

    public MultiLevelCascadeOptionFunction(final SearchHandlerManager searchHandlerManager, final CustomFieldManager customFieldManager) {
        this.jqlCascadingSelectLiteralUtil = notNull("jqlCascadingSelectLiteralUtil", ComponentAccessor.getComponentOfType(JqlCascadingSelectLiteralUtil.class));
        this.customFieldManager = notNull("customFieldManager", customFieldManager);
        this.searchHandlerManager = notNull("searchHandlerManager", searchHandlerManager);
        this.jqlSelectOptionsUtil = notNull("jqlSelectOptionsUtil", ComponentAccessor.getComponentOfType(JqlSelectOptionsUtil.class));
    }

    /**
     * validates the input args and operand
     * (non-Javadoc)
     *
     * @see com.atlassian.jira.plugin.jql.function.JqlFunction#validate(com.atlassian.crowd.embedded.api.User,
     *      com.atlassian.query.operand.FunctionOperand, com.atlassian.query.clause.TerminalClause)
     */
    public MessageSet validate(final User searcher, final FunctionOperand operand, final TerminalClause terminalClause) {
        final MessageSet messageSet = new MessageSetImpl();
        final List<String> args = operand.getArgs();

        final Set<CustomField> fields = resolveField(false, searcher, terminalClause.getName());
        if (fields.isEmpty()) {
            messageSet.addErrorMessage(getI18n().getText("jira.jql.function.cascade.option.not.cascade.field", terminalClause.getName(), getFunctionName()));
            return messageSet;
        }

        if (args.isEmpty()) {
            messageSet.addErrorMessage(getI18n().getText("jira.jql.function.cascade.option.incorrect.args", getFunctionName()));
            return messageSet;
        }

        String parentArg = args.get(0);
        // "none" is okay as parent, so long as it is the only argument
        if (isEmptyArg(parentArg)) {
            if (args.size() > 1) {
                messageSet.addErrorMessage(getI18n().getText("jira.jql.function.cascade.option.not.parent", getFunctionName(), parentArg));
                return messageSet;
            }
        } else {
            // check that the parents are visible
            parentArg = cleanArg(parentArg);
            final Collection<Option> parentOptions = getParentOptions(operand, fields, parentArg);
            if (parentOptions.isEmpty()) {
                messageSet.addErrorMessage(getI18n().getText("jira.jql.function.cascade.option.not.parent", getFunctionName(), parentArg));
                return messageSet;
            }

            if (args.size() > 1) {
                // accumulate the options from the child argument which are actually children of the specified parents and are visible
                final List<String> children = args.subList(1, args.size());
                Collection<Option> previousOptions = parentOptions;
                for (String childArg : children) {
                    if (!isEmptyArg(childArg)) {
                        childArg = cleanArg(childArg);
                        final Collection<Option> chosenOptions = getRepresentedChildrenOptions(operand, fields, previousOptions, childArg);

                        // if none of the children options belonged to the parents
                        if (chosenOptions.isEmpty()) {
                            messageSet.addErrorMessage(getI18n().getText("jira.jql.function.cascade.option.parent.children.doesnt.match", childArg, parentArg, getFunctionName()));
                            return messageSet;
                        }
                        previousOptions = chosenOptions;
                    }
                }
            }
        }
        return messageSet;
    }

    /**
     * Note: this method returns unconvential query literals. All
     * {@link com.atlassian.jira.jql.operand.QueryLiteral}s returned will have Long values, but they
     * may be either positive or negative. Positive values indicate that the option ids should be
     * included in the results, whereas negative ids mean that they should be excluded.
     *
     * @param queryCreationContext the context of query creation
     * @param operand              the operand to get values from
     * @param terminalClause       the terminal clause that contains the operand
     * @return a list of query literals following the scheme described above; never null.
     */
    public List<QueryLiteral> getValues(final QueryCreationContext queryCreationContext, final FunctionOperand operand, final TerminalClause terminalClause) {
        notNull("queryCreationContext", queryCreationContext);
        LinkedList<Option> orderedOptions = new LinkedList<Option>();
        List<QueryLiteral> result = new ArrayList<QueryLiteral>();
        final List<String> args = operand.getArgs();
        final Set<CustomField> fields = resolveField(queryCreationContext.isSecurityOverriden(), queryCreationContext.getUser(), terminalClause.getName());
        if (!args.isEmpty() && !fields.isEmpty()) {
            String parent = args.get(0);
            if (isEmptyArg(parent)) {
                // only return the right result if it validates correctly i.e. if the "none" is on its own
                return args.size() == 1 ? Collections.singletonList(new QueryLiteral(operand)) : Collections.<QueryLiteral>emptyList();
            }
            parent = cleanArg(parent);
            Collection<Option> parentOptions = getParentOptions(operand, fields, parent);
            for (Option parentOpt : parentOptions)
                orderedOptions.addLast(parentOpt);
            for (int j = 1; j < args.size(); j++) {
                String childArg = args.get(j);
                if (isEmptyArg(childArg)) {
                    // build up all the child options from the given parents - these are the negative options
                    final Collection<Option> childOptions = new HashSet<Option>();
                    for (Option parentOption : parentOptions) {
                        childOptions.addAll(parentOption.getChildOptions());
                    }
                    result = createLiterals(operand, orderedOptions, childOptions);
                    return result;
                } else {
                    childArg = cleanArg(childArg);
                    Set<Option> childOptions = filterToDepth(j, getOptions(operand, fields, childArg));
                    for (Option child : childOptions)
                        orderedOptions.addLast(child);
                    parentOptions = childOptions;
                }
            }
            /*the result is built with ordered options from the parent to the last child, to allow a good query construction*/
            result = createLiterals(operand, orderedOptions, Collections.<Option>emptySet());

        }
        return result;
    }

    private Set<Option> filterToDepth(final int depth, final Set<Option> options) {
        return Sets.filter(options, new Predicate<Option>() {
            @Override
            public boolean apply(Option input) {
                return depth == MultiLevelCascadingSelectCFType.findDepth(input);
            }
        });
    }



    public int getMinimumNumberOfExpectedArguments() {
        return 1;
    }

    public JiraDataType getDataType() {
        return JiraDataTypes.CASCADING_OPTION;
    }

    // /CLOVER:OFF
    List<QueryLiteral> createLiterals(final Operand operand, final Collection<Option> positiveOptions, final Collection<Option> negativeOptions) {
        return jqlCascadingSelectLiteralUtil.createQueryLiteralsFromOptions(operand, positiveOptions, negativeOptions);
    }

    // /CLOVER:ON

    private static boolean isEmptyArg(final String arg) {
        return EMPTY_VALUE.equalsIgnoreCase(arg);
    }

    /**
     * @param arg the option argument
     * @return the non-quoted string of the {@link #EMPTY_VALUE} argument if it was specified; the
     *         input otherwise.
     */
    private static String cleanArg(final String arg) {
        return QUOTED_EMPTY_VALUE.equalsIgnoreCase(arg) ? EMPTY_VALUE : arg;
    }

    /**
     * @param overrideSecurity false if only fields which the user can see should be resolved
     * @param searcher         the user performing the search
     * @param clauseName       the clause name used
     * @return the set of {@link com.atlassian.jira.issue.fields.CustomField}s which map to the clause
     *         name and are also of the type
     *         {@link com.atlassian.jira.issue.customfields.impl.CascadingSelectCFType}; never null.
     */
    private Set<CustomField> resolveField(final boolean overrideSecurity, final User searcher, final String clauseName) {
        final Set<CustomField> fields = new HashSet<CustomField>();
        final Collection<String> fieldIds = overrideSecurity ? searchHandlerManager.getFieldIds(clauseName) : searchHandlerManager.getFieldIds(searcher, clauseName);
        for (String fieldId : fieldIds) {
            final CustomField field = customFieldManager.getCustomFieldObject(fieldId);
            if (field != null && field.getCustomFieldType() instanceof CascadingSelectCFType) {
                fields.add(field);
            } else {
                log.info(String.format("jql clause name %s does not resolve to a cascading select field", clauseName));
            }
        }
        return fields;
    }

    /**
     * Accumulates the options from the child argument which are actually children of the specified
     * parents and are visible.
     *
     * @param operand          the function operand
     * @param fields           the {@link com.atlassian.jira.issue.fields.CustomField}s to retreive the options from
     * @param parentOptionList the list of parent options from the parent argument
     * @param childArg         the String representation of the child from the function arguments
     * @return the collection of children options which were represented by the child argument of the
     *         function and were children of at least one of the specified parents.
     */
    private Collection<Option> getRepresentedChildrenOptions(final Operand operand, final Set<CustomField> fields, final Collection<Option> parentOptionList, final String childArg) {
        final Set<Option> argumentOptions = getOptions(operand, fields, childArg);
        final Set<Option> chosenOptions = new HashSet<Option>();

        for (Option parentOption : parentOptionList) {
            final List<Option> children = parentOption.getChildOptions();
            chosenOptions.addAll(intersection(children, argumentOptions));
        }

        return chosenOptions;
    }

    /**
     * @param operand   the function operand
     * @param fields    the {@link com.atlassian.jira.issue.fields.CustomField}s to retreive the options from
     * @param optionArg the string argument representing a parent option
     * @return the intersection of the list of options represented by the argument which are parents
     *         and those which are visible
     */
    private Collection<Option> getParentOptions(final Operand operand, final Set<CustomField> fields, final String optionArg) {
        final Collection<Option> possibleParents = getOptions(operand, fields, optionArg);

        // filter down the list of parents to those which are actually parents
        Iterator<Option> parentIterator = possibleParents.iterator();
        while (parentIterator.hasNext()) {
            final Option parentOption = parentIterator.next();
            if (parentOption.getParentOption() != null) {
                parentIterator.remove();
            }
        }

        return possibleParents;
    }

    /**
     * @param operand       the function operand
     * @param fields        the {@link com.atlassian.jira.issue.fields.CustomField}s to retreive the options from
     * @param optionArg     the string argument representing an option
     * @return the intersection of the list of options represented by the argument and those which are
     *         visible
     */
    private Set<Option> getOptions(final Operand operand, final Set<CustomField> fields, final String optionArg) {
        final Set<Option> optionList = new HashSet<Option>();
        for (CustomField customField : fields) {
            optionList.addAll(jqlSelectOptionsUtil.getOptions(customField, new QueryLiteral(operand, optionArg), true));
        }
        return optionList;
    }

    /**
     * @param c1 the first collection
     * @param c2 the second collection
     * @return the set (ie no duplicates) of elements T that are contained in both c1 and c2; never
     *         null.
     */
    private static <T> Set<T> intersection(Collection<T> c1, Collection<T> c2) {
        Set<T> intersection = new HashSet<T>();
        for (T t : c1) {
            if (c2.contains(t)) {
                intersection.add(t);
            }
        }
        return intersection;
    }
}
