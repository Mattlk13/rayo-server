package com.rayo.storage.cassandra;

import org.junit.Before;
import org.junit.BeforeClass;

import com.rayo.storage.BaseGatewayStorageServiceTest;
import com.rayo.storage.DefaultGatewayStorageService;
import com.rayo.storage.lb.RoundRobinLoadBalancer;

public class CassandraGatewayStorageServiceTest extends BaseGatewayStorageServiceTest {

    @BeforeClass
    public static void startCassandraServer() throws Exception {

    	EmbeddedCassandraTestServer.start();
    }  
    
	@Before
	public void setup() throws Exception {
		
		CassandraDatastore cassandraDatastore = new CassandraDatastore();
		cassandraDatastore.setCreateSampleApplication(false);
		cassandraDatastore.getSchemaHandler().setWaitForSyncing(false);
		cassandraDatastore.init();
		storageService = new DefaultGatewayStorageService();
		storageService.setDefaultPlatform("staging");
		storageService.setStore(cassandraDatastore);
		
		loadBalancer = new RoundRobinLoadBalancer();
		loadBalancer.setStorageService(storageService);
	}
}