/**
 * Licensed to the Apache Software Foundation (ASF) under one or more

 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import com.sonicsw.xq.XQServiceContext;
import com.sonicsw.xqimpl.util.log.XQLogImpl;

public class SonicInterceptorImpl implements MethodInterceptor{

	private Transport transport;
	private Converter<XQMessage> converter;
	private XQLog logger;
	
	public SonicInterceptorImpl(){
		logger = XQLogImpl.getInstance();
		logger.logInformation("Init Liquid interceptor.");
		ApplicationContext appCtx = new ClassPathXmlApplicationContext("com.pte.liquid.relay.sonic/application-context.xml");        
		transport = (Transport) appCtx.getBean("relayApiJmsTransport");
		if(transport!=null)
		logger.logInformation("Done initializing transport.");
		converter = (Converter<XQMessage>) appCtx.getBean("relaySonicConverter");
		logger.logInformation("Done initializing converter.");
		logger.logInformation("Done init Liquid interceptor.");
	}
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Method method = invocation.getMethod();
		Object args[] = invocation.getArguments();
		String name = method.getName();		
		
		if (name.equals("service")) {
			return adviseService(invocation, (XQServiceContext) args[0]);
		}else
			return invocation.proceed();
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
					correlationID = determineCorrelation(context, message);
					setCorrelationID(correlationID, context, message);
					Message preMsg = converter.convert(message);
					preMsg.setCorrelationID(correlationID);
					preMsg.setLocation(determineLocation(context.getParameters()));
					transport.send(preMsg);
					
				} catch (Exception e) {
					//Empty by design
				}

			}

			
			// perform invocation
			
			rval = invocation.proceed();
			// add post-invocaton custom code here
									
			Iterator<XQAddress> addresses  = context.getProcessContext().getNextAddresses();
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
		return params.getParameter(XQConstants.PARAM_SERVICE_NAME,	XQConstants.PARAM_STRING);		
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
			}else if(context.getProcessContext().getInflightProperties().containsKey(Constants.CORRELATION_ID_PROPERTY_NAME)){
				correlationId = message.getStringHeader(context.getProcessContext().getInflightProperties().getProperty(Constants.CORRELATION_ID_PROPERTY_NAME));
			}else{
				correlationId = UUID.randomUUID().toString();
			}
			
		}	
		return correlationId;
	}
	
	private void setCorrelationID(String correlationID, XQServiceContext context, XQMessage message) throws XQMessageException{
		context.getProcessContext().getInflightProperties().setProperty(Constants.CORRELATION_ID_PROPERTY_NAME, correlationID);
		message.setStringHeader(Constants.CORRELATION_ID_PROPERTY_NAME, correlationID);
		
	}
	
}
