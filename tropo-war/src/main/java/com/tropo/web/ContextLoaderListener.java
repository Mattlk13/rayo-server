package com.tropo.web;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;

public class ContextLoaderListener extends org.springframework.web.context.ContextLoaderListener {

	public static final String TROPO_STATUS = "tropo.status";
	
	@Override
	public void contextInitialized(ServletContextEvent event) {

		super.contextInitialized(event);
		event.getServletContext().setAttribute(TROPO_STATUS, TropoStatus.SUCCESSFUL);
	}
	
	@Override
	protected WebApplicationContext createWebApplicationContext(
			ServletContext sc, ApplicationContext parent) {

		try {
			return super.createWebApplicationContext(sc, parent);
		} catch (RuntimeException re) {
			sc.setAttribute(TROPO_STATUS, TropoStatus.FAILED);
			throw re;
		}
	}
	
	@Override
	public void contextDestroyed(ServletContextEvent event) {
		
		super.contextDestroyed(event);
	}
}