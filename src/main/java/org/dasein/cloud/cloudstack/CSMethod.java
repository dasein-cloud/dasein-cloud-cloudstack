/**
 * Copyright (C) 2009-2015 Dell, Inc.
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.cloudstack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.SignatureException;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.dasein.cloud.*;
import org.dasein.cloud.util.APITrace;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.SAXException;

public class CSMethod {
    static public class ParsedError {
        public int code;
        public String message;
    }
    
    static public final String CREATE_KEYPAIR = "createSSHKeyPair";
    static public final String DELETE_KEYPAIR = "deleteSSHKeyPair";
    static public final String LIST_KEYPAIRS  = "listSSHKeyPairs";

    private CSCloud provider;
    
    public CSMethod(@Nonnull CSCloud provider) { this.provider = provider; }
    
    private String buildUrl(String command, Param ... params) throws CloudException, InternalException {
        ProviderContext ctx = provider.getContext();


        String apiShared = "";
        String apiSecret = "";
        try {
            List<ContextRequirements.Field> fields = provider.getContextRequirements().getConfigurableValues();
            for(ContextRequirements.Field f : fields ) {
                if(f.type.equals(ContextRequirements.FieldType.KEYPAIR)){
                    byte[][] keyPair = (byte[][])ctx.getConfigurationValue(f);
                    apiShared = new String(keyPair[0], "utf-8");
                    apiSecret = new String(keyPair[1], "utf-8");
                }
            }
        }
        catch (UnsupportedEncodingException ignore) {}

        if( ctx == null ) {
            throw new GeneralCloudException("No context was set for this request", CloudErrorType.GENERAL);
        }
        try {
            StringBuilder str = new StringBuilder();
            String apiKey = apiShared;
            String accessKey = apiSecret;

            StringBuilder newKey = new StringBuilder();
            for( int i =0; i<apiKey.length(); i++ ) {
                char c = apiKey.charAt(i);
                
                if( c != '\r' ) {
                    newKey.append(c);
                }
            }
            apiKey = newKey.toString();
            newKey = new StringBuilder();
            for( int i =0; i<accessKey.length(); i++ ) {
                char c = accessKey.charAt(i);
                
                if( c != '\r' ) {
                    newKey.append(c);
                }
            }
            accessKey = newKey.toString();
            str.append(ctx.getCloud().getEndpoint());

            // Make sure the url ends up exactly as http://x.x.x.x:y/client/api?command=
            // otherwise the server may choke like we've found it does for uploadSslCert command.
            while( str.lastIndexOf("/") == str.length()-1 ) {
                str.deleteCharAt(str.length()-1);
            }
            if( !str.toString().endsWith("/api") ) {
                str.append("/api");
            }
            str.append("?command=");
            str.append(command);
            for( Param param : params ) {
                str.append("&");
                str.append(param.getKey());
                if( param.getValue() != null ) {
                    str.append("=");
                    str.append(URLEncoder.encode(param.getValue(), "UTF-8").replaceAll("\\+", "%20"));
                }
            }
            str.append("&apiKey=");
            str.append(URLEncoder.encode(apiKey, "UTF-8").replaceAll("\\+", "%20"));
            str.append("&signature=");
            try {
                str.append(URLEncoder.encode(getSignature(command, apiKey, accessKey, params), "UTF-8").replaceAll("\\+", "%20"));
            }
            catch( SignatureException e ) {
                throw new InternalException(e);
            }
            return str.toString();
        }
        catch( UnsupportedEncodingException e ) {
            e.printStackTrace();
            throw new RuntimeException("This cannot happen: " + e.getMessage());
        }
    }
    
    private byte[] calculateHmac(String data, String key) throws SignatureException {
        try {
            SecretKeySpec signingKey = new SecretKeySpec(key.getBytes(), "HmacSHA1");
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(signingKey);
        
            return mac.doFinal(data.getBytes());
        } 
        catch (Exception e) {
            throw new SignatureException("Failed to generate HMAC : " + e.getMessage());
        }
    }

    protected @Nonnull HttpClient getClient(String url) throws InternalException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new InternalException("No context was specified for this request");
        }
        boolean ssl = url.startsWith("https");
        HttpParams params = new BasicHttpParams();

        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
        //noinspection deprecation
        HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);
        HttpProtocolParams.setUserAgent(params, "Dasein Cloud");

        Properties p = ctx.getCustomProperties();

        if( p != null ) {
            String proxyHost = p.getProperty("proxyHost");
            String proxyPort = p.getProperty("proxyPort");

            if( proxyHost != null ) {
                int port = 0;

                if( proxyPort != null && proxyPort.length() > 0 ) {
                    port = Integer.parseInt(proxyPort);
                }
                params.setParameter(ConnRoutePNames.DEFAULT_PROXY, new HttpHost(proxyHost, port, ssl ? "https" : "http"));
            }
        }
        return new DefaultHttpClient(params);
    }

    public @Nonnull Document get(@Nonnull String command, @Nonnull List<Param> params) throws CloudException, InternalException {
        return get(command, params.toArray(new Param[params.size()]));
    }

    public @Nonnull Document get(@Nonnull String command, Param ... params) throws CloudException, InternalException {
        Logger wire = CSCloud.getLogger(CSMethod.class, "wire");
        Logger logger = CSCloud.getLogger(CSMethod.class, "std");
        String url = buildUrl(command, params);
        if( logger.isTraceEnabled() ) {
            logger.trace("enter - " + CSMethod.class.getName() + ".get(" + url + ")");
        }
        if( wire.isDebugEnabled() ) {
            wire.debug("[" + (new Date()) + "] -------------------------------------------------------------------");
            wire.debug("");
        }
        HttpClient client = null;
        try {
            HttpGet get = new HttpGet(url);
            client = getClient(url);
            HttpResponse response;

            get.addHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            //get.getParams().setCookiePolicy(CookiePolicy.IGNORE_COOKIES);
            if( wire.isDebugEnabled() ) {
                wire.debug(get.getRequestLine().toString());
                for( Header header : get.getAllHeaders() ) {
                    wire.debug(header.getName() + ": " + header.getValue());
                }
                wire.debug("");
            }
            try {
                APITrace.trace(provider, command);
                response = client.execute(get);
            }
            catch( IOException e ) {
                logger.error("I/O error from server communications: " + e.getMessage());
                e.printStackTrace();
                throw new InternalException(e);
            }
            int status = response.getStatusLine().getStatusCode();
            if( logger.isDebugEnabled() ) {
                logger.debug("get(): HTTP Status " + status);
            }
            if( wire.isDebugEnabled() ) {
                Header[] headers = response.getAllHeaders();
                
                wire.debug(response.getStatusLine().toString());
                for( Header h : headers ) {
                    if( h.getValue() != null ) {
                        wire.debug(h.getName() + ": " + h.getValue().trim());
                    }
                    else {
                        wire.debug(h.getName() + ":");
                    }
                }
                wire.debug("");                                
            }
            try {
                if( status != HttpServletResponse.SC_OK ) {
                    HttpEntity entity = response.getEntity();
                    String body = (entity == null ? null : EntityUtils.toString(entity));

                    if( body == null ) {
                        CSMethod.ParsedError p = new CSMethod.ParsedError();

                        p.code = status;
                        p.message = "No error information was provided";
                        throw new CSException(CloudErrorType.GENERAL, p);
                    }
                    if( body.contains("<html>") ) {
                        if( status == HttpServletResponse.SC_FORBIDDEN || status == HttpServletResponse.SC_UNAUTHORIZED ) {
                            CSMethod.ParsedError p = new CSMethod.ParsedError();
                            
                            p.code = status;
                            p.message = body;
                            throw new CSException(CloudErrorType.AUTHENTICATION, p);
                        }
                        else if( status == 430 || status == 431 || status == 432 || status == 436 ) {
                            return null;
                        }
                        CSMethod.ParsedError p = new CSMethod.ParsedError();
                        
                        p.code = status;
                        p.message = body;
                        throw new CSException(p);
                    }
                    throw new CSException(parseError(status, body));
                }
                HttpEntity entity = response.getEntity();

                return parseResponse(status, EntityUtils.toString(entity));
            }
            catch( NoHttpResponseException e ) {
                throw new CommunicationException("No answer from endpoint: " + e.getMessage());
            }
            catch( IOException e ) {
                throw new CommunicationException("IOException getting stream: " + e.getMessage());
            }
        }
        finally {
            if( wire.isDebugEnabled() ) {
                wire.debug("");
                wire.debug("[" + (new Date()) + "] -------------------------------------------------------------------");
            }
            if( logger.isTraceEnabled() ) {
                logger.trace("exit - " + CSMethod.class.getName() + ".get()");
            }
            if( client != null ) {
                client.getConnectionManager().shutdown();
            }
        }
    }

    private String getSignature(String command, String apiKey, String accessKey, Param ... params) throws UnsupportedEncodingException, SignatureException {
        Logger logger = CSCloud.getLogger(CSMethod.class, "std");
        
        if( logger.isTraceEnabled() ) {
            logger.trace("enter - " + CSMethod.class.getName() + ".getSignature(" + command + "," + apiKey + "," + accessKey + ",[params])");
        }
        try {
            TreeSet<Param> sorted = new TreeSet<Param>();
            StringBuilder str = new StringBuilder();
            
            sorted.add(new Param("command", URLEncoder.encode(command, "UTF-8").replaceAll("\\+", "%20").toLowerCase()));
            sorted.add(new Param("apikey", URLEncoder.encode(apiKey, "UTF-8").replaceAll("\\+", "%20").toLowerCase()));
            for( Param param : params ) {
                sorted.add(new Param(param.getKey().toLowerCase(), URLEncoder.encode(param.getValue(), "UTF-8").replaceAll("\\+", "%20").toLowerCase()));
            }
            boolean first = true;
            for( Param param : sorted ) {
                if( !first ) {
                    str.append("&");
                }
                first = false;
                str.append(param.getKey());
                str.append("=");
                str.append(param.getValue());
            }
            if( logger.isDebugEnabled()  ) { 
                logger.debug("getSignature(): String to sign=" + str.toString());
            }
            return new String(Base64.encodeBase64(calculateHmac(str.toString(), accessKey)));
        }
        finally {
            if( logger.isTraceEnabled() ) {
                logger.trace("exit - " + CSMethod.class.getName() + ".getSignature()");
            }
        }
    }
    
    private ParsedError parseError(int httpStatus, String assumedXml) throws InternalException {
        Logger logger = CSCloud.getLogger(CSMethod.class, "std");
        
        if( logger.isTraceEnabled() ) {
            logger.trace("enter - " + CSMethod.class.getName() + ".parseError(" + httpStatus + "," + assumedXml + ")");
        }
        try {
            ParsedError error = new ParsedError();
            
            error.code = httpStatus;
            error.message = null;
            try {
                Document doc = parseResponse(httpStatus, assumedXml);
                
                NodeList codes = doc.getElementsByTagName("errorcode");
                for( int i=0; i<codes.getLength(); i++ ) {
                    Node n = codes.item(i);
                    
                    if( n != null && n.hasChildNodes() ) {
                        error.code = Integer.parseInt(n.getFirstChild().getNodeValue().trim());
                    }
                }
                NodeList text = doc.getElementsByTagName("errortext");
                for( int i=0; i<text.getLength(); i++ ) {
                    Node n = text.item(i);
                    
                    if( n != null && n.hasChildNodes() ) {
                        error.message = n.getFirstChild().getNodeValue();
                    }
                }
            }
            catch( Throwable ignore ) {
                logger.warn("parseError(): Error was unparsable: " + ignore.getMessage());
                if( error.message == null ) {
                    error.message = assumedXml;
                }
            }
            if( error.message == null ) {
                if( httpStatus == 401 ) {
                    error.message = "Unauthorized user";
                }
                else if( httpStatus == 430 ) {
                    error.message = "Malformed parameters";
                }
                else if( httpStatus == 547 || httpStatus == 530 ) {
                    error.message = "Server error in cloud (" + httpStatus + ")";
                }
                else if( httpStatus == 531 ) {
                    error.message = "Unable to find account";
                }
                else {
                    error.message = "Received error code from server: " + httpStatus;
                }
            }
            return error;
        }
        finally {
            if( logger.isTraceEnabled() ) {
                logger.trace("exit - " + CSMethod.class.getName() + ".parseError()");
            }
        }
    }

    private @Nonnull Document parseResponse(int code, String xml) throws CloudException, InternalException {
        Logger wire = CSCloud.getLogger(CSMethod.class, "wire");
        Logger logger = CSCloud.getLogger(CSMethod.class, "std");

        if( logger.isTraceEnabled() ) {
            logger.trace("enter - " + CSMethod.class.getName() + ".parseResponse(" + xml + ")");
        }
        try {
            try {
                ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes("utf-8"));

                Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
                if( wire.isDebugEnabled() ) {
                    wire.debug(prettifyXml(doc));
                }
                return doc;
            }
            catch( IOException e ) {
                if( wire.isDebugEnabled() ) {
                    wire.debug(xml);
                }
                throw new CommunicationException(" " + e.getMessage());
            }
            catch( ParserConfigurationException e ) {
                if( wire.isDebugEnabled() ) {
                    wire.debug(xml);
                }
                throw new CommunicationException(" " + e.getMessage());
            }
            catch( SAXException e ) {
                if( wire.isDebugEnabled() ) {
                    wire.debug(xml);
                }
                throw new CommunicationException("Received error code from server [" + code + "]: " + xml);
            }
        }
        finally {
            if( logger.isTraceEnabled() ) {
                logger.trace("exit - " + CSMethod.class.getName() + ".parseResponse()");
            }
        }
    }

    private String prettifyXml( Document doc ) {
        try {
            DOMImplementationLS impl = ( DOMImplementationLS ) DOMImplementationRegistry.newInstance().getDOMImplementation("LS");
            LSSerializer writer = impl.createLSSerializer();
            writer.getDomConfig().setParameter("format-pretty-print", Boolean.TRUE);
            return writer.writeToString(doc);
        }
        catch( Exception e ) {
            throw new RuntimeException(e);
        }
    }
}
