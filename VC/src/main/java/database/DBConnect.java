/******************************************************************************
 * This research was sponsored by the U.S. Army Research Laboratory and the
 * U.K. Ministry of Defence under the Biennial Program Plane 2013 (BPP13),
 * Project 6, Task 3: Collaborative Intelligence Analysis.
 * The U.S. and U.K. Governments are authorized to reproduce and distribute
 * reprints for Government purposes notwithstanding any copyright notation
 * hereon.
 * **************************************************************************
 * 
 * This class connects to the database and uses database connection pool
 * The results are copied and handled here, then everything gets closed so not to create  memory leaks
 * 
 * @author      Alice Toniolo  
 * @version     2.0  
 * @since 		July 2017          
 *   
 */


package database;
import java.sql.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;


public class DBConnect {

		private static Logger log;
        PreparedStatement deletePreparedStatement = null;
        PreparedStatement insertPreparedStatement = null;
		
		private Connection dbCon;
		private boolean inTransaction;

		/**
		 * Test hook. Production leaves this unset and borrows from the Tomcat pool.
		 * Package-visible so a Derby-backed test can run without JNDI.
		 */
		interface ConnectionFactory {
			Connection open() throws SQLException;
		}

		private static volatile ConnectionFactory connectionFactory;

		static void setConnectionFactory(ConnectionFactory factory) {
			connectionFactory = factory;
		}

		public DBConnect(){
			log=Logger.getLogger(getClass().getName());
		}

		private Connection openConnection() throws SQLException, NamingException {
			if (connectionFactory != null) {
				return connectionFactory.open();
			}
			Context initContext = new InitialContext();
			Context envContext = (Context) initContext.lookup("java:/comp/env");
			javax.sql.DataSource dataSource = (javax.sql.DataSource) envContext.lookup("jdbc/myDB");
			return dataSource.getConnection();
		}

		private synchronized boolean connect(){
			try {
				if (inTransaction && dbCon != null && !dbCon.isClosed()) {
					return true;
				}
				closeConnectionQuietly();
				dbCon = openConnection();
				return dbCon != null;
	        } catch (NamingException | SQLException e) {
	        	log.log(Level.SEVERE,"FAIL DB CONNECTION",e);
	            e.printStackTrace();
	            return false;
	        }
		}
	 

		public synchronized ArrayList<HashMap<String,Object>> execSQL(String sql) throws SQLException {
			ResultSet rs = null;
			Statement s = null;
			try{
				if (!connect() || dbCon == null) {
					throw new SQLException("FAIL DB CONNECTION");
				}
				s= dbCon.createStatement();
				rs= s.executeQuery(sql);
		 
			return convertResultSetToList(rs);
			
			}catch(SQLException e){
				log.log(Level.SEVERE,"FAIL DB CONNECTION",e);
				throw e;
			}catch(Exception e){
				log.log(Level.SEVERE,"FAIL DB CONNECTION",e);
				throw new SQLException("FAIL DB CONNECTION", e);
			}finally{
			    if (rs != null) {
			        try {
			            rs.close();
			        } catch (SQLException e) { /* ignored */}
			    }
			    if (s != null) {
			        try {
			            s.close();
			        } catch (SQLException e) { /* ignored */}
			    }
			    if (dbCon != null) {
			        try {
			        	dbCon.close();
			        } catch (SQLException e) { /* ignored */}
			    }
			}
		}
		
		public synchronized boolean updateSQL(String sql){
			boolean res=false;
			Statement s = null;
			try{
				connect();
				s = dbCon.createStatement();
				System.out.println(sql);
				s.executeUpdate(sql);
		
				res=true;
			}catch(Exception e){
				log.log(Level.SEVERE,"FAIL DB CONNECTION",e);
				res=false;
			}finally{
			    if (s != null) {
			        try {
			            s.close();
			        } catch (SQLException e) { /* ignored */}
			    }
			    if (dbCon != null) {
			        try {
			        	dbCon.close();
			        } catch (SQLException e) { /* ignored */}
			    } 
			}
			return res;
		}
		
		 
		
		public synchronized boolean existTable(String table){
			Statement s = null;
			boolean res=false;
			ResultSet rs = null;
			try {
				connect();
				String sql= "SELECT * FROM "+table;
				s=dbCon.createStatement();
				rs= s.executeQuery(sql);
		 
			
			// check if "employee" table is there
				if(rs!=null){
					res=true;
				}
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				log.log(Level.INFO,"TABLE NOT FOUND");
				res=false;
			} finally {
				   if (rs != null) {
				        try {
				            rs.close();
				        } catch (SQLException e) { /* ignored */}
				    }
				    if (s != null) {
				        try {
				           s.close();
				        } catch (SQLException e) { /* ignored */}
				    }
				    if (dbCon != null) {
				        try {
				        	dbCon.close();
				        } catch (SQLException e) { /* ignored */}
				    }
			 
			}
			//System.out.println(res);
			return res;
		}

		public void tryConnect(){
			connect();
		}
	
		private ArrayList<HashMap<String,Object>> convertResultSetToList(ResultSet rs) throws SQLException {
		    ResultSetMetaData md = rs.getMetaData();
		    int columns = md.getColumnCount();
		    ArrayList<HashMap<String,Object>> list = new ArrayList<HashMap<String,Object>>();

		    while (rs.next()) {
		        HashMap<String,Object> row = new HashMap<String, Object>(columns);
		        for(int i=1; i<=columns; ++i) {
		        	//import org.apache.derby.client.am.Clob;
                    /*
                    j.robinson@software.ac.uk - 
                    Prevent Exception - java.sql.SQLException: Stream or LOB value cannot be retrieved more than once
                    http://db.apache.org/derby/releases/release-10.6.1.0.cgi#Note+for+DERBY-3844
                    
		        	Object test=rs.getObject(i);
		        	if(test instanceof java.sql.Clob){
		        		test=rs.getString(i);
		        	}
                    */
                    String test=rs.getString(i);
		            row.put(md.getColumnName(i).toLowerCase(),test);
		        }
		        list.add(row);
		    }

		    return list;
		}

		 

		public void forceClose() {
			 if (dbCon != null) {
			        try {
			        	dbCon.close();
			        } catch (SQLException e) { /* ignored */}
			    }
			
		}


	/**
	 * Prepared update. String values, including CLOB snapshots, are bound with setString —
	 * the same binding the previous single-placeholder insertClob used.
	 * @return true when the statement was executed
	 */
	public synchronized boolean insertClob(String updateStatement, Object... values){
		return executeUpdate(updateStatement, values);
	}

	public synchronized ArrayList<HashMap<String,Object>> executeQuery(String sql, Object... params) throws SQLException {
		ArrayList<HashMap<String,Object>> result = new ArrayList<HashMap<String,Object>>();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			if (!connect() || dbCon == null) {
				throw new SQLException("FAIL DB CONNECTION");
			}
			ps = dbCon.prepareStatement(sql);
			bind(ps, params);
			rs = ps.executeQuery();
			result = convertResultSetToList(rs);
		} catch (SQLException e) {
			log.log(Level.SEVERE, "FAIL DB QUERY", e);
			throw e;
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException e) { /* ignored */ }
			}
			closeQuietly(ps);
			if (!inTransaction) {
				closeConnectionQuietly();
			}
		}
		return result;
	}

	public synchronized boolean executeUpdate(String sql, Object... params) {
		PreparedStatement ps = null;
		try {
			if (!connect()) {
				return false;
			}
			ps = dbCon.prepareStatement(sql);
			bind(ps, params);
			ps.executeUpdate();
			return true;
		} catch (SQLException e) {
			log.log(Level.SEVERE, "FAIL DB UPDATE", e);
			return false;
		} finally {
			closeQuietly(ps);
			if (!inTransaction) {
				closeConnectionQuietly();
			}
		}
	}

	public synchronized boolean beginTransaction() {
		if (!connect()) {
			return false;
		}
		try {
			dbCon.setAutoCommit(false);
			inTransaction = true;
			return true;
		} catch (SQLException e) {
			log.log(Level.SEVERE, "FAIL DB BEGIN", e);
			inTransaction = false;
			closeConnectionQuietly();
			return false;
		}
	}

	public synchronized boolean commitTransaction() {
		return finishTransaction(true);
	}

	public synchronized void rollback() {
		finishTransaction(false);
	}

	private boolean finishTransaction(boolean commit) {
		boolean ok = true;
		try {
			if (dbCon != null && inTransaction) {
				if (commit) {
					dbCon.commit();
				} else {
					dbCon.rollback();
				}
			} else if (commit) {
				ok = false;
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE, commit ? "FAIL DB COMMIT" : "FAIL DB ROLLBACK", e);
			ok = false;
			if (commit && dbCon != null) {
				try {
					dbCon.rollback();
				} catch (SQLException ignored) { /* connection is abandoned */ }
			}
		} finally {
			inTransaction = false;
			if (dbCon != null) {
				try {
					if (!dbCon.isClosed()) {
						dbCon.setAutoCommit(true);
					}
				} catch (SQLException ignored) { /* pool will reset on return */ }
			}
			closeConnectionQuietly();
		}
		return ok;
	}

	private static void bind(PreparedStatement ps, Object... params) throws SQLException {
		if (params == null) {
			return;
		}
		for (int i = 0; i < params.length; i++) {
			Object value = params[i];
			int index = i + 1;
			if (value == null) {
				ps.setNull(index, Types.VARCHAR);
			} else if (value instanceof SqlNull) {
				ps.setNull(index, ((SqlNull) value).jdbcType);
			} else if (value instanceof Timestamp) {
				ps.setTimestamp(index, (Timestamp) value);
			} else if (value instanceof Boolean) {
				ps.setBoolean(index, ((Boolean) value).booleanValue());
			} else if (value instanceof Integer) {
				ps.setInt(index, ((Integer) value).intValue());
			} else if (value instanceof Long) {
				ps.setLong(index, ((Long) value).longValue());
			} else {
				ps.setString(index, value.toString());
			}
		}
	}

	private void closeQuietly(Statement statement) {
		if (statement != null) {
			try {
				statement.close();
			} catch (SQLException ignored) { /* ignored */ }
		}
	}

	private void closeConnectionQuietly() {
		if (dbCon != null) {
			try {
				dbCon.close();
			} catch (SQLException ignored) { /* ignored */ }
			dbCon = null;
		}
	}


//	public void setAutoCommit(boolean b) {
//		try {
//		    tryConnect();
//			this.dbCon.setAutoCommit(b);
//			dbCon.close();
//		} catch (SQLException e) {
//			e.printStackTrace();
//		}
//	}

    public void prepareDeleteStatementInTransaction(String sql) {
        try {
            deletePreparedStatement = dbCon.prepareStatement(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void prepareInsertStatementInTransaction(String sql3) {
        try {
            insertPreparedStatement = dbCon.prepareStatement(sql3);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void commit() {
        try {
            dbCon.commit();
            dbCon.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}


/*
 * A database connection pool creates and manages a pool of connections to a database. Recycling and reusing already existing connections to a database is more efficient than opening a new connection.

There is one problem with connection pooling. A web application has to explicitly close ResultSet's, Statement's, and Connection's. Failure of a web application to close these resources can result in them never being available again for reuse, a database connection pool "leak". This can eventually result in your web application database connections failing if there are no more available connections.

There is a solution to this problem. The Apache Commons DBCP can be configured to track and recover these abandoned database connections. Not only can it recover them, but also generate a stack trace for the code which opened these resources and never closed them.

To configure a DBCP DataSource so that abandoned database connections are removed and recycled add the following attribute to the Resource configuration for your DBCP DataSource:

removeAbandoned="true"
When available database connections run low DBCP will recover and recycle any abandoned database connections it finds. The default is false.

Use the removeAbandonedTimeout attribute to set the number of seconds a database connection has been idle before it is considered abandoned.

removeAbandonedTimeout="60"
The default timeout for removing abandoned connections is 300 seconds.

The logAbandoned attribute can be set to true if you want DBCP to log a stack trace of the code which abandoned the database connection resources.

logAbandoned="true"
The default is false.
*/
