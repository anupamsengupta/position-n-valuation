package com.power.posval.domain.port.datasource;

import javax.sql.DataSource;

/**
 * Port for writer/reader datasource routing. Pattern #22.
 * Implementation in pv-persistence: DualHikariDataSourceRouter.
 */
public interface DataSourceRouter {

    DataSource writerDataSource();

    DataSource readerDataSource();
}
