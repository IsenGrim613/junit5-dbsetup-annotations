package com.github.isengrim613.junit5;

import com.ninja_squad.dbsetup.DbSetup;
import com.ninja_squad.dbsetup.DbSetupTracker;
import com.ninja_squad.dbsetup.bind.BinderConfiguration;
import com.ninja_squad.dbsetup.bind.DefaultBinderConfiguration;
import com.ninja_squad.dbsetup.destination.DataSourceDestination;
import com.ninja_squad.dbsetup.operation.Operation;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.platform.commons.util.ExceptionUtils;
import org.junit.platform.commons.util.ReflectionUtils;

import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.ninja_squad.dbsetup.Operations.sequenceOf;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotatedFields;
import static org.junit.platform.commons.util.AnnotationUtils.isAnnotated;
import static org.junit.platform.commons.util.ReflectionUtils.isStatic;
import static org.junit.platform.commons.util.ReflectionUtils.makeAccessible;

/**
 * The main processor for the extension.
 *
 * @see #postProcessTestInstance(Object, ExtensionContext)
 * @see #beforeEach(ExtensionContext)
 *
 * @see com.github.isengrim613.junit5.DbSetup
 * @see DbSetupSource
 * @see DbSetupOperation
 * @see DbSetupSkipNext
 */
public class DbSetupExtension implements TestInstancePostProcessor, BeforeEachCallback {
    private static final Logger LOGGER = Logger.getLogger(DbSetupExtension.class.getName());
    private static final String DB_SETUP_HOLDERS_KEY = "DB_SETUP_HOLDERS";

    private static void validateDataSourceExists(Map<String, Field> dataSourceFields, Map<Field, String[]> fields) {
        Set<String> dataSourceSet = new HashSet<>();
        for (String[] dataSources : fields.values()) {
            dataSourceSet.addAll(Arrays.asList(dataSources));
        }

        for (String dataSource : dataSourceSet) {
            if (!dataSourceFields.keySet().contains(dataSource)) {
                throw new IllegalArgumentException("This data sources does not exist: " + dataSource);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method gathers and sorts the {@link DbSetupSource} and {@link DbSetupOperation}s for each test instance.
     */
    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        Map<String, Field> dataSourceFields = findDataSourceFields(context);
        Map<Field, String[]> binderConfigurationFields = findBinderConfigurationFields(context);
        LinkedHashMap<Field, String[]> operationFields = findOperationFields(context);

        // make sure all binder configuration data sources exists
        validateDataSourceExists(dataSourceFields, binderConfigurationFields);

        // make sure all operation's data sources exists
        validateDataSourceExists(dataSourceFields, operationFields);

        // map operations to data sources
        List<DbSetupHolder> holders = new ArrayList<>();
        for (Map.Entry<String, Field> dataSourceEntry : dataSourceFields.entrySet()) {
            List<Field> operationsForDataSourceFields = new ArrayList<>();

            for (Map.Entry<Field, String[]> operationFieldEntry : operationFields.entrySet()) {
                if (Arrays.asList(operationFieldEntry.getValue()).contains(dataSourceEntry.getKey())) {
                    operationsForDataSourceFields.add(operationFieldEntry.getKey());
                }
            }

            Field binderConfigurationField = null;

            for (Map.Entry<Field, String[]> binderConfigurationFieldEntry : binderConfigurationFields.entrySet()) {
                if (Arrays.asList(binderConfigurationFieldEntry.getValue()).contains(dataSourceEntry.getKey())) {
                    if (binderConfigurationField != null) {
                        throw new IllegalArgumentException("There is more than 1 BinderConfiguration for DataSource: " + dataSourceEntry.getKey());
                    }

                    binderConfigurationField = binderConfigurationFieldEntry.getKey();
                }
            }

            LOGGER.log(Level.FINE, "Found {0} operations for {1} data source", new Object[] { operationsForDataSourceFields.size(), dataSourceEntry.getKey() });
            holders.add(new DbSetupHolder(dataSourceEntry.getValue(), operationsForDataSourceFields, binderConfigurationField));
        }

        getStore(context, testInstance).put(DB_SETUP_HOLDERS_KEY, holders);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method launches the {@link DbSetupOperation}s against the {@link DbSetupSource} before each test.
     */
    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        @SuppressWarnings("unchecked")
        List<DbSetupHolder> holders = (List<DbSetupHolder>) getStore(context).get(DB_SETUP_HOLDERS_KEY);

        for (DbSetupHolder holder : holders) {
            holder.launch(context);
        }
    }

    private static Map<String, Field> findDataSourceFields(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        List<Field> dbSetupSources = findAnnotatedFieldsInHierarchy(testClass, DbSetupSource.class);

        if (dbSetupSources.isEmpty()) {
            throw new IllegalArgumentException("No @DbSetupSource found");
        }

        Map<String, Field> dbSetupSourcesMap = new HashMap<>();
        for (Field field : dbSetupSources) {
            makeAccessible(field);
            checkField(field, DataSource.class, "@DbSetupSource");

            DbSetupSource dataSourceAnnotation = field.getAnnotation(DbSetupSource.class);
            String dataSourceName = dataSourceAnnotation.name();

            if (dbSetupSourcesMap.put(dataSourceName, field) != null) {
                throw new IllegalArgumentException("There is more than 1 @DbSetupSource named: " + dataSourceName);
            }
        }

        return dbSetupSourcesMap;
    }

    private static Map<Field, String[]> findBinderConfigurationFields(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        List<Field> dbSetupBinderConfigurationElements =
                findAnnotatedFieldsInHierarchy(testClass, DbSetupBinderConfiguration.class);

        Map<Field, String[]> result = new HashMap<>();
        if (dbSetupBinderConfigurationElements.isEmpty()) {
            return result;
        }

        for (Field field : dbSetupBinderConfigurationElements) {
            makeAccessible(field);
            checkField(field, BinderConfiguration.class, "@DbSetupBinderConfiguration");

            DbSetupBinderConfiguration configurationAnnotation = field.getAnnotation(DbSetupBinderConfiguration.class);
            String[] dataSources = configurationAnnotation.sources();

            result.put(field, dataSources);
        }

        return result;
    }

    private static LinkedHashMap<Field, String[]> findOperationFields(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        List<Field> dbSetupOperationElements = findAnnotatedFieldsInHierarchy(testClass, DbSetupOperation.class);

        if (dbSetupOperationElements.isEmpty()) {
            LOGGER.log(Level.FINE, "There are no @DbSetupOperation for {0}", new Object[] { testClass.getName() });
        }

        // we must sort it in a list first
        dbSetupOperationElements.sort(Comparator.comparingInt(DbSetupExtension::getOperationOrder));

        // because if we use TreeMaps(Comparator) to sort, duplicated sort entries will be lost
        LinkedHashMap<Field, String[]> orderedMap = new LinkedHashMap<>();
        for (Field field : dbSetupOperationElements) {
            makeAccessible(field);
            checkField(field, Operation.class, "@DbSetupOperation");

            DbSetupOperation operationAnnotation = field.getAnnotation(DbSetupOperation.class);
            String[] dataSources = operationAnnotation.sources();

            orderedMap.put(field, dataSources);
        }

        return orderedMap;
    }

    private ExtensionContext.Store getStore(ExtensionContext context, Object testInstance) {
        return context.getStore(ExtensionContext.Namespace.create(DbSetupExtension.class, testInstance));
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        Object testInstance = context.getRequiredTestInstance();
        return context.getStore(ExtensionContext.Namespace.create(DbSetupExtension.class, testInstance));
    }

    private static void checkField(Field field, Class<?> returnType, String name) {
        if (!returnType.isAssignableFrom(field.getType())) {
            throw new IllegalArgumentException(name + " should return an instance or subclass of " + returnType);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getFieldValue(Field field, Object instance) throws Exception {
        if (isStatic(field)) {
            return (T) field.get(null);
        }
        else {
            instance = matchElementDeclaringClass(field.getDeclaringClass(), instance);
            return (T) field.get(instance);
        }
    }

    private static List<Field> findAnnotatedFieldsInHierarchy(Class<?> clazz, Class<? extends Annotation> annotationType) {
        List<Field> fields = new ArrayList<>();

        if (clazz.getDeclaringClass() != null) {
            fields.addAll(findAnnotatedFieldsInHierarchy(clazz.getDeclaringClass(), annotationType));
        }

        fields.addAll(findAnnotatedFields(clazz, annotationType, f -> true, ReflectionUtils.HierarchyTraversalMode.TOP_DOWN));
        return fields;
    }

    private static Object matchElementDeclaringClass(Class<?> elementDeclaringClass, Object instance) {
        //while (elementDeclaringClass != instance.getClass()) {
        while (!elementDeclaringClass.isAssignableFrom(instance.getClass())) {
            Optional<Object> outerOption = getOuterInstance(instance);
            if (!outerOption.isPresent()) {
                throw new IllegalArgumentException("Cannot map outer instance to outer methods found");
            }

            instance = outerOption.get();
        }

        return instance;
    }

    private static Optional<Object> getOuterInstance(Object inner) {
        // This is risky since it depends on the name of the field which is nowhere guaranteed
        // but has been stable so far in all JDKs
        return Arrays.stream(inner.getClass().getDeclaredFields())
                .filter(field -> field.getName().startsWith("this$"))
                .findFirst()
                .map(field -> {
                    try {
                        return makeAccessible(field).get(inner);
                    }
                    catch (Throwable t) {
                        throw ExceptionUtils.throwAsUncheckedException(t);
                    }
                });
    }

    private static int getOperationOrder(Field field) {
        DbSetupOperation dbSetupOperation = field.getAnnotation(DbSetupOperation.class);
        int order = dbSetupOperation.order();

        if (order < 0) {
            String fieldName = field.getName();
            int lastInteger = getLastInt(fieldName);
            if (lastInteger < 0) {
                throw new IllegalArgumentException("No order specified and implicit order cannot be determined by " +
                        "inspecting the field name");
            }
            else {
                order = lastInteger;
            }
        }

        return order;
    }

    private static int getLastInt(String line) {
        int offset = line.length();
        for (int i = line.length() - 1; i >= 0; i--) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) {
                offset--;
            }
            else {
                if (offset == line.length()) {
                    // No int at the end
                    return -1;
                }
                return Integer.parseInt(line.substring(offset));
            }
        }
        return Integer.parseInt(line.substring(offset));
    }

    private static class DbSetupHolder {
        private Field dataSourceDestinationField;
        private List<Field> operationFields;
        private Field binderConfigurationField;
        private DbSetupTracker dbSetupTracker;

        public DbSetupHolder(Field dataSourceDestinationField, List<Field> operationFields, Field binderConfigurationField) {
            this.dataSourceDestinationField = dataSourceDestinationField;
            this.operationFields = operationFields;
            this.dbSetupTracker = new DbSetupTracker();
            this.binderConfigurationField = binderConfigurationField;
        }

        public void launch(ExtensionContext context) throws Exception {
            LOGGER.log(Level.FINE, "Launching {0} operations", new Object[] { operationFields.size() });
            if (operationFields.isEmpty()) {
                return;
            }

            Object testInstance = context.getRequiredTestInstance();

            DataSource dataSource = getFieldValue(dataSourceDestinationField, testInstance);
            List<Operation> operations = new ArrayList<>();
            for (Field field : operationFields) {
                operations.add(getFieldValue(field, testInstance));
            }

            BinderConfiguration binderConfiguration = binderConfigurationField != null ?
                    getFieldValue(binderConfigurationField, testInstance) : DefaultBinderConfiguration.INSTANCE;

            DataSourceDestination dataSourceDestination = new DataSourceDestination(dataSource);
            DbSetup dbSetup = new DbSetup(dataSourceDestination, sequenceOf(operations), binderConfiguration);
            dbSetupTracker.launchIfNecessary(dbSetup);

            Method testMethod = context.getRequiredTestMethod();
            if (isAnnotated(testMethod, DbSetupSkipNext.class)) {
                LOGGER.log(Level.FINE, "Skipping next db setup for {0}", testMethod.getName());
                dbSetupTracker.skipNextLaunch();
            }
        }
    }
}
