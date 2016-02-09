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

package org.dasein.cloud.cloudstack.identity;

import org.dasein.cloud.*;
import org.dasein.cloud.cloudstack.CSCloud;
import org.dasein.cloud.cloudstack.CSMethod;
import org.dasein.cloud.cloudstack.Param;
import org.dasein.cloud.identity.*;
import org.dasein.cloud.util.APITrace;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Implements the CSCloud 3.0 SSH keypair support
 * @author George Reese
 * @since 2012.02
 * @version 2012.02
 */
public class Keypair extends AbstractShellKeySupport<CSCloud> {
    private CSCloud provider;
    private transient volatile KeypairCapabilities capabilities;

    Keypair(@Nonnull CSCloud provider) { super(provider); }

    @Override
    public @Nonnull SSHKeypair createKeypair(@Nonnull String name) throws InternalException, CloudException {
        APITrace.begin(provider, "Keypair.createKeypair");
        try {
            final Document doc = new CSMethod(provider).get(
                    CSMethod.CREATE_KEYPAIR,
                    new Param("name", name)
            );
            NodeList matches = doc.getElementsByTagName("keypair");

            for( int i=0; i<matches.getLength(); i++ ) {
                SSHKeypair key = toKeypair(matches.item(i));
                if( key != null ) {
                    return key;
                }
            }
            throw new GeneralCloudException("Request did not error, but no keypair was generated", CloudErrorType.GENERAL);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void deleteKeypair(@Nonnull String providerId) throws InternalException, CloudException {
        APITrace.begin(provider, "Keypair.deleteKeypair");
        try {
            new CSMethod(provider).get(
                    CSMethod.DELETE_KEYPAIR,
                    new Param("name", providerId)
            );
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nullable String getFingerprint(@Nonnull String providerId) throws InternalException, CloudException {
        APITrace.begin(provider, "Keypair.getFingerprint");
        try {
            SSHKeypair keypair = getKeypair(providerId);
            return (keypair == null ? null : keypair.getFingerprint());
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    @Deprecated
    public Requirement getKeyImportSupport() throws CloudException, InternalException {
        return Requirement.NONE;
    }

    @Override
    public @Nullable SSHKeypair getKeypair(@Nonnull String providerId) throws InternalException, CloudException {
        APITrace.begin(provider, "Keypair.getKeypair");
        try {
            final Document doc = new CSMethod(provider).get(
                    CSMethod.LIST_KEYPAIRS,
                    new Param("name", providerId)
            );
            NodeList matches = doc.getElementsByTagName("sshkeypair");
            for( int i=0; i<matches.getLength(); i++ ) {
                final SSHKeypair key = toKeypair(matches.item(i));
                if( key != null ) {
                    return key;
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    @Nonnull @Override public ShellKeyCapabilities getCapabilities() throws CloudException, InternalException {
        if( capabilities == null ) {
            capabilities = new KeypairCapabilities(provider);
        }
        return capabilities;
    }

    @Override
    public @Nonnull SSHKeypair importKeypair(@Nonnull String name, @Nonnull String publicKey) throws InternalException, CloudException {
        throw new OperationNotSupportedException("Import of keypairs is not supported");
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(provider, "Keypair.isSubscribed");
        try {
            return provider.getComputeServices().getVirtualMachineSupport().isSubscribed();
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Collection<SSHKeypair> list() throws InternalException, CloudException {
        APITrace.begin(provider, "Keypair.list");
        try {
            final CSMethod method = new CSMethod(provider);
            Document doc = method.get(CSMethod.LIST_KEYPAIRS);
            final List<SSHKeypair> keys = new ArrayList<>();

            int numPages = 1;
            NodeList nodes = doc.getElementsByTagName("count");
            Node n = nodes.item(0);
            if (n != null) {
                String value = n.getFirstChild().getNodeValue().trim();
                int count = Integer.parseInt(value);
                numPages = count/500;
                int remainder = count % 500;
                if (remainder > 0) {
                    numPages++;
                }
            }

            for (int page = 1; page <= numPages; page++) {
                if (page > 1) {
                    String nextPage = String.valueOf(page);
                    doc = method.get(
                            CSMethod.LIST_KEYPAIRS,
                            new Param("pagesize", "500"),
                            new Param("page", nextPage)
                    );
                }
                NodeList matches = doc.getElementsByTagName("sshkeypair");
                for( int i=0; i<matches.getLength(); i++ ) {
                    SSHKeypair key = toKeypair(matches.item(i));

                    if( key != null ) {
                        keys.add(key);
                    }
                }
            }
            return keys;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        return new String[0];
    }
    
    private @Nullable SSHKeypair toKeypair(@Nullable Node node) throws CloudException, InternalException {
        if( node == null || !node.hasChildNodes() ) {
            return null;
        }
        String regionId = getContext().getRegionId();
        if( regionId == null ) {
            throw new InternalException("No region is part of this request");
        }

        NodeList attributes = node.getChildNodes();
        SSHKeypair kp = new SSHKeypair();
        String privateKey = null;
        String fingerprint = null;
        String name = null;
        
        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attribute = attributes.item(i);
            
            if( attribute != null ) {
                String nodeName = attribute.getNodeName();
                
                if( nodeName.equalsIgnoreCase("name") && attribute.hasChildNodes() ) {
                    name = attribute.getFirstChild().getNodeValue().trim(); 
                }
                else if( nodeName.equalsIgnoreCase("fingerprint") && attribute.hasChildNodes() ) {
                    fingerprint = attribute.getFirstChild().getNodeValue().trim();
                }
                else if( nodeName.equalsIgnoreCase("privatekey") && attribute.hasChildNodes() ) {
                    privateKey = attribute.getFirstChild().getNodeValue().trim();
                }
            }
        }
        if( name == null || fingerprint == null ) {
            return null;
        }
        kp.setProviderRegionId(regionId);
        kp.setProviderOwnerId(getContext().getAccountNumber());
        kp.setProviderKeypairId(name);
        kp.setName(name);
        kp.setFingerprint(fingerprint);
        if( privateKey != null ) {
            try {
                kp.setPrivateKey(privateKey.getBytes("utf-8"));
            }
            catch( UnsupportedEncodingException e ) {
                throw new InternalException(e);
            }
        }
        return kp;
    }
}
