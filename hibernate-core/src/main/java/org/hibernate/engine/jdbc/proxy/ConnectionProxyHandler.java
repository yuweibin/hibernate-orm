/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2010, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.engine.jdbc.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.hibernate.engine.jdbc.spi.JdbcResourceRegistry;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.jdbc.spi.ConnectionObserver;
import org.hibernate.engine.jdbc.spi.LogicalConnectionImplementor;

/**
 * The {@link InvocationHandler} for intercepting messages to {@link java.sql.Connection} proxies.
 *
 * @author Steve Ebersole
 */
public class ConnectionProxyHandler extends AbstractProxyHandler implements InvocationHandler, ConnectionObserver {
	private static final Logger log = LoggerFactory.getLogger( ConnectionProxyHandler.class );

	private LogicalConnectionImplementor logicalConnection;

	public ConnectionProxyHandler(LogicalConnectionImplementor logicalConnection) {
		super( logicalConnection.hashCode() );
		this.logicalConnection = logicalConnection;
		this.logicalConnection.addObserver( this );
	}

	/**
	 * Access to our logical connection.
	 *
	 * @return the logical connection
	 */
	protected LogicalConnectionImplementor getLogicalConnection() {
		errorIfInvalid();
		return logicalConnection;
	}

	/**
	 * Get reference to physical connection.
	 * <p/>
	 * NOTE : be sure this handler is still valid before calling!
	 *
	 * @return The physical connection
	 */
	private Connection extractPhysicalConnection() {
		return logicalConnection.getConnection();
	}

	/**
	 * Provide access to JDBCServices.
	 * <p/>
	 * NOTE : package-protected
	 *
	 * @return JDBCServices
	 */
	JdbcServices getJdbcServices() {
		return logicalConnection.getJdbcServices();
	}

	/**
	 * Provide access to JDBCContainer.
	 * <p/>
	 * NOTE : package-protected
	 *
	 * @return JDBCContainer
	 */
	JdbcResourceRegistry getResourceRegistry() {
		return logicalConnection.getResourceRegistry();
	}

	protected Object continueInvocation(Object proxy, Method method, Object[] args) throws Throwable {
		String methodName = method.getName();
		log.trace( "Handling invocation of connection method [{}]", methodName );

		// other methods allowed while invalid ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		if ( "close".equals( methodName ) ) {
			explicitClose();
			return null;
		}

		errorIfInvalid();

		// handle the JDBC 4 Wrapper#isWrapperFor and Wrapper#unwrap calls
		//		these cause problems to the whole proxy scheme though as we need to return the raw objects
		if ( "isWrapperFor".equals( methodName ) && args.length == 1 ) {
			return method.invoke( extractPhysicalConnection(), args );
		}
		if ( "unwrap".equals( methodName ) && args.length == 1 ) {
			return method.invoke( extractPhysicalConnection(), args );
		}

		if ( "getWrappedObject".equals( methodName ) ) {
			return extractPhysicalConnection();
		}

		try {
			Object result = method.invoke( extractPhysicalConnection(), args );
			result = wrapIfNecessary( result, proxy, method, args );

			return result;
		}
		catch( InvocationTargetException e ) {
			Throwable realException = e.getTargetException();
			if ( SQLException.class.isInstance( realException ) ) {
				throw logicalConnection.getJdbcServices().getSqlExceptionHelper()
						.convert( ( SQLException ) realException, realException.getMessage() );
			}
			else {
				throw realException;
			}
		}
	}

	private Object wrapIfNecessary(Object result, Object proxy, Method method, Object[] args) {
		String methodName = method.getName();
		Object wrapped = result;
		if ( "createStatement".equals( methodName ) ) {
			wrapped = ProxyBuilder.buildStatement(
					(Statement) result,
					this,
					( Connection ) proxy
			);
			getResourceRegistry().register( ( Statement ) wrapped );
		}
		else if ( "prepareStatement".equals( methodName ) ) {
			wrapped = ProxyBuilder.buildPreparedStatement(
					( String ) args[0],
					(PreparedStatement) result,
					this,
					( Connection ) proxy
			);
			getResourceRegistry().register( ( Statement ) wrapped );
		}
		else if ( "prepareCall".equals( methodName ) ) {
			wrapped = ProxyBuilder.buildCallableStatement(
					( String ) args[0],
					(CallableStatement) result,
					this,
					( Connection ) proxy
			);
			getResourceRegistry().register( ( Statement ) wrapped );
		}
		else if ( "getMetaData".equals( methodName ) ) {
			wrapped = ProxyBuilder.buildDatabaseMetaData( (DatabaseMetaData) result, this, ( Connection ) proxy );
		}
		return wrapped;
	}

	private void explicitClose() {
		if ( isValid() ) {
			invalidateHandle();
		}
	}

	private void invalidateHandle() {
		log.trace( "Invalidating connection handle" );
		logicalConnection = null;
		invalidate();
	}

	/**
	 * {@inheritDoc}
	 */
	public void physicalConnectionObtained(Connection connection) {
	}

	/**
	 * {@inheritDoc}
	 */
	public void physicalConnectionReleased() {
		log.info( "logical connection releasing its physical connection");
	}

	/**
	 * {@inheritDoc}
	 */
	public void logicalConnectionClosed() {
		log.info( "*** logical connection closed ***" );
		invalidateHandle();
	}
}