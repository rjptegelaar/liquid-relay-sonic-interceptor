//Copyright 2015 Paul Tegelaar
//
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
package com.pte.liquid.relay.sonic.interceptor;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.UUID;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.pte.liquid.relay.Constants;
import com.pte.liquid.relay.Converter;
import com.pte.liquid.relay.Transport;
import com.pte.liquid.relay.model.Message;
import com.sonicsw.xq.XQAddress;
import com.sonicsw.xq.XQConstants;
import com.sonicsw.xq.XQEnvelope;
import com.sonicsw.xq.XQLog;
import com.sonicsw.xq.XQMessage;
import com.sonicsw.xq.XQMessageException;
import com.sonicsw.xq.XQParameters;
import com.sonicsw.xq.XQProcessContext;
import com.sonicsw.xq.XQServiceContext;
import com.sonicsw.xqimpl.util.log.XQLogImpl;

public class SonicInterceptorImpl implements MethodInterceptor{


	private final static String ESB_TYPE_PROPERTY_VALUE = "SONIC";
	
	private Transport transport;
	private Converter<XQMessage> converter;
	private XQLog logger;				
	
	public SonicInterceptorImpl(){
		logger = XQLogImpl.getInstance();
		logger.logDebug("Init Liquid interceptor.");
		ApplicationContext appCtx = new ClassPathXmlApplicationContext("com.pte.liquid.relay.sonic/application-context.xml");        
		transport = (Transport) appCtx.getBean("legacyAcyncTransport");
		if(transport!=null)
		logger.logDebug("Done initializing transport.");
		converter = (Converter<XQMessage>) appCtx.getBean("relaySonicConverter");
		logger.logDebug("Done initializing converter.");
		logger.logDebug("Done init Liquid interceptor.");
	}
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Method method = invocation.getMethod();
		Object args[] = invocation.getArguments();
		String name = method.getName();		
		
		if (name.equals("service")) {
			return adviseService(invocation, (XQServiceContext) args[0]);
		} else if(name.equals("destroy")){			
			transport.destroy();
			return invocation.proceed();
		} else {			
			return invocation.proceed();
		}
	}

	public Object adviseService(MethodInvocation invocation,
			XQServiceContext context) throws Throwable {
		Object rval = null;
		XQMessage message = null;
		String correlationID = "";
		
		try {
			
			if (context != null) {				
				try {
					message = context.getFirstIncoming().getMessage();					
					
					//Set correlation ID on inflight message
					correlationID = determineCorrelation(context, message);
					setCorrelationID(correlationID, context, message);
					
					//Set order on inflight message
					int order = determineOrder(context, message);
					setOrder(order, context, message);
										
					Message preMsg = converter.convert(message);

					//Set parent ID on log message
					String parentID = determineParent(context, message);					
					preMsg.setParentID(parentID);
					
					//Set new parent ID on inflight message
					String messageID = preMsg.getId();
					setParentID(messageID, context, message);
					
					//Set correlation ID on log message
					preMsg.setCorrelationID(correlationID);
					
					//Set location on log message
					preMsg.setLocation(determineLocation(context.getParameters()));
					
					//Set order on log message
					preMsg.setOrder(order);
					//Set ESB type
					preMsg.setHeader(Constants.ESB_TYPE_PROPERTY_NAME, ESB_TYPE_PROPERTY_VALUE);
					
					if(logger.isDebugLoggingEnabled())
						logger.logDebug(preMsg.toString());
					
					transport.send(preMsg);
					
				} catch (Exception e) {
					//Empty by design
				}

			}

			
			// perform invocation
			
			rval = invocation.proceed();
			// add post-invocaton custom code here
			
			
			
			if(context!=null){
				XQProcessContext processContext = context.getProcessContext();
				if(processContext!=null){
					Iterator<XQAddress> addresses = processContext.getNextAddresses();
					if(addresses!=null){
						while(addresses.hasNext()){
							XQAddress address = addresses.next();
							if(XQConstants.ADDRESS_ENDPOINT == address.getType() || XQConstants.ADDRESS_REPLY_TO == address.getType()){								
								while(context.hasNextIncoming()){									
									XQEnvelope env = context.getNextIncoming();
									message = env.getMessage();
									message.setStringHeader(Constants.CORRELATION_ID_PROPERTY_NAME, correlationID);
								}													
							}									
						}
					}
				}
			}					
			return rval;
		} catch (Throwable ex) {
			throw ex;
		}
	}
	

	private String getContainerName(XQParameters params){
		return params.getParameter(XQConstants.PARAM_CONTAINER_NAME, XQConstants.PARAM_STRING);
	}
	
	private String getESBContainerName(XQParameters params){	
		return params.getParameter(XQConstants.PARAM_XQ_CONTAINER_NAME,	XQConstants.PARAM_STRING);
	}
	
	private String getDomainName(XQParameters params){			
		return params.getParameter(XQConstants.PARAM_DOMAIN_NAME,	XQConstants.PARAM_STRING);		
	}

	private String getServiceName(XQParameters params){			
		return params.getParameter(XQConstants.PARAM_PROCESS_STEP,	XQConstants.PARAM_STRING);		
		
	}

	private String getProcessName(XQParameters params){			
		return params.getParameter(XQConstants.PARAM_PROCESS_NAME,	XQConstants.PARAM_STRING);		
	}
	
	private String determineLocation(XQParameters params){
		StringBuffer sb = new StringBuffer();
		sb.append(getDomainName(params));
		sb.append(Constants.LOCATION_SEPERATOR);
		sb.append(getContainerName(params));
		sb.append(Constants.LOCATION_SEPERATOR);
		sb.append(getESBContainerName(params));
		sb.append(Constants.LOCATION_SEPERATOR);
		sb.append(getProcessName(params));
		sb.append(Constants.LOCATION_SEPERATOR);
		sb.append(getServiceName(params));
		
		return sb.toString();
	}
	
	
	private String determineCorrelation(XQServiceContext context, XQMessage message) throws XQMessageException{
		String correlationId = "";	
		if(context!=null && message!=null){
			if(message.containsHeader(Constants.CORRELATION_ID_PROPERTY_NAME)){
				correlationId = message.getStringHeader(Constants.CORRELATION_ID_PROPERTY_NAME);
			} else {
				correlationId = UUID.randomUUID().toString();
			}
		}	
		return correlationId;
	}
	
	private int determineOrder(XQServiceContext context, XQMessage message) throws XQMessageException{
		int order = 0;	
		if(context!=null && message!=null){
			if(message.containsHeader(Constants.ORDER_PROPERTY_NAME)){
				order = message.getIntHeader(Constants.ORDER_PROPERTY_NAME);
			}			
		}	
		return order;
	}
	
	private String determineParent(XQServiceContext context, XQMessage message) throws XQMessageException{
		String parentID = "";	
		if(context!=null && message!=null){
			if(message.containsHeader(Constants.PARENT_ID_PROPERTY_NAME)){
				parentID = message.getStringHeader(Constants.PARENT_ID_PROPERTY_NAME);
			}			
		}	
		return parentID;
	}
	
	private void setCorrelationID(String correlationID, XQServiceContext context, XQMessage message) throws XQMessageException{		
		message.setStringHeader(Constants.CORRELATION_ID_PROPERTY_NAME, correlationID);		
	}
	
	private void setOrder(int order, XQServiceContext context, XQMessage message) throws XQMessageException{		
		message.setIntHeader(Constants.ORDER_PROPERTY_NAME, order + 1);		
	}
	
	private void setParentID(String parentID, XQServiceContext context, XQMessage message) throws XQMessageException{		
		message.setStringHeader(Constants.PARENT_ID_PROPERTY_NAME, parentID);		
	}
	
}
