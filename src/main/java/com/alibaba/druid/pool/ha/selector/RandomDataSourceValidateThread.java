package com.alibaba.druid.pool.ha.selector;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A Thread trying to test all DataSource provided by HADataSource.
 * If a DataSource failed this test for 3 times, it will be put into a blacklist.
 *
 * @author DigitalSonic
 */
public class RandomDataSourceValidateThread implements Runnable {
    private final static Log LOG = LogFactory.getLog(RandomDataSourceValidateThread.class);
    private int sleepSeconds = 30;
    private int blacklistThreshold = 3;

    private RandomDataSourceSelector selector;
    private Map<String, Integer> errorCounts = new HashMap<String, Integer>();

    public RandomDataSourceValidateThread(RandomDataSourceSelector selector) {
        this.selector = selector;
    }

    @Override
    public void run() {
        while (true) {
            if (selector != null) {
                checkAllDataSources();
                maintainBlacklist();
            }
            try {
                Thread.sleep(sleepSeconds * 1000);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    private void maintainBlacklist() {
        Map<String, DataSource> dataSourceMap = selector.getDataSourceMap();
        for (Map.Entry<String, Integer> e : errorCounts.entrySet()) {
            if (e.getValue() <= 0) {
                selector.removeBlacklist(dataSourceMap.get(e.getKey()));
            } else if (e.getValue() >= blacklistThreshold) {
                LOG.warn("Adding " + e.getKey() + " to blacklist.");
                selector.addBlacklist(dataSourceMap.get(e.getKey()));
            }
        }
    }

    private void checkAllDataSources() {
        Map<String, DataSource> dataSourceMap = selector.getDataSourceMap();
        for (Map.Entry<String, DataSource> e : dataSourceMap.entrySet()) {
            if (!(e.getValue() instanceof DruidDataSource)) {
                continue;
            }
            boolean flag = check(e.getKey(), (DruidDataSource) e.getValue());

            if (flag) {
                errorCounts.put(e.getKey(), 0);
            } else {
                if (!errorCounts.containsKey(e.getKey())) {
                    errorCounts.put(e.getKey(), 0);
                }
                int count = errorCounts.get(e.getKey());
                errorCounts.put(e.getKey(), count + 1);
            }
        }
    }

    private boolean check(String name, DruidDataSource dataSource) {
        boolean result = true;
        Driver driver = dataSource.getRawDriver();
        Properties info = dataSource.getConnectProperties();
        String url = dataSource.getRawJdbcUrl();
        Connection conn = null;

        try {
            LOG.debug("Validating " + name + " every " + sleepSeconds + " seconds.");
            conn = driver.connect(url, info);
            dataSource.validateConnection(conn);
        } catch (SQLException e) {
            LOG.warn("Validation FAILED for " + name + ". Exception: " + e.getMessage());
            result = false;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    LOG.error("Can not close connection for HostAndPort Validation.", e);
                }
            }
        }

        return result;
    }

    public void setSleepSeconds(int sleepSeconds) {
        this.sleepSeconds = sleepSeconds;
    }

    public void setBlacklistThreshold(int blacklistThreshold) {
        this.blacklistThreshold = blacklistThreshold;
    }
}
