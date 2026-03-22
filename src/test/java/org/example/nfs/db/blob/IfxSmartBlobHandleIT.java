package org.example.nfs.db.blob;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration test for {@link IfxSmartBlobHandle} against a real Informix database.
 *
 * <p>Disabled by default — requires an Informix instance and the {@code ifxjdbc.jar}
 * on the classpath.
 *
 * <p>To run: {@code ./gradlew test -Ddb.informix=true -Dinformix.url=jdbc:informix-sqli://host:port/db:user=…;password=…}
 */
@Disabled("Requires Informix — run with -Ddb.informix=true")
@EnabledIfSystemProperty(named = "db.informix", matches = "true")
class IfxSmartBlobHandleIT extends BlobHandleContractTest {

    private static final long TEST_ID = 1L;

    @Override
    protected BlobHandle createHandle() throws Exception {
        // Obtain a real Informix connection from the system property
        String url = System.getProperty("informix.url");
        if (url == null) {
            throw new IllegalStateException("informix.url system property must be set");
        }
        java.sql.Connection conn = java.sql.DriverManager.getConnection(url);
        return new IfxSmartBlobHandle(conn, TEST_ID);
    }

    @Override
    protected BlobHandle openHandle() throws Exception {
        return createHandle();
    }
}
