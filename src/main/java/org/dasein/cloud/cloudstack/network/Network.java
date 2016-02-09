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

package org.dasein.cloud.cloudstack.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.dasein.cloud.*;
import org.dasein.cloud.cloudstack.CSCloud;
import org.dasein.cloud.cloudstack.CSException;
import org.dasein.cloud.cloudstack.CSMethod;
import org.dasein.cloud.cloudstack.Param;
import org.dasein.cloud.compute.VirtualMachine;
import org.dasein.cloud.network.AbstractVLANSupport;
import org.dasein.cloud.network.Firewall;
import org.dasein.cloud.network.FirewallSupport;
import org.dasein.cloud.network.InternetGateway;
import org.dasein.cloud.network.IpAddressSupport;
import org.dasein.cloud.network.IPVersion;
import org.dasein.cloud.network.Networkable;
import org.dasein.cloud.network.NetworkServices;
import org.dasein.cloud.network.RoutingTable;
import org.dasein.cloud.network.VLAN;
import org.dasein.cloud.network.VLANCapabilities;
import org.dasein.cloud.network.VLANState;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.util.uom.time.Hour;
import org.dasein.util.uom.time.TimePeriod;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class Network extends AbstractVLANSupport<CSCloud> {
    static public final Logger logger = Logger.getLogger(Network.class);

    static public final String CREATE_NETWORK         = "createNetwork";
    static public final String DELETE_NETWORK         = "deleteNetwork";
    static public final String LIST_NETWORK_OFFERINGS = "listNetworkOfferings";
    static public final String LIST_NETWORKS          = "listNetworks";

    static public final String CREATE_EGRESS_RULE = "createEgressFirewallRule";

    Network(CSCloud provider) {
        super(provider);
    }

    public List<String> findFreeNetworks() throws CloudException, InternalException {
        ArrayList<String> vlans = new ArrayList<String>();
        // FIXME: evidently "shared" networks are not supported for VM deploy, i.e. I can't launch a VM into someone else's
        //        network. Also CS UI only shows my own networks there.
        //        for( VLAN n : listDefaultNetworks(true, true) ) {
        //            if( n != null ) {
        //                vlans.add(n.getProviderVlanId());
        //            }
        //        }
        for( VLAN n : listDefaultNetworks(false, true) ) {
            if( n != null && !vlans.contains(n.getProviderVlanId()) ) {
                vlans.add(n.getProviderVlanId());
            }
        }
        return vlans;
    }

    private transient volatile CSVlanCapabilities capabilities;

    @Nonnull @Override
    public VLANCapabilities getCapabilities() throws InternalException, CloudException {
        if( capabilities == null ) {
            capabilities = new CSVlanCapabilities(getProvider());
        }
        return capabilities;
    }

    static public class NetworkOffering {
        public String availability;
        public String networkType;
        public String offeringId;
    }

    public @Nonnull Collection<NetworkOffering> getNetworkOfferings( @Nonnull String regionId ) throws InternalException, CloudException {
        Cache<NetworkOffering> cache = null;

        if( regionId.equals(getContext().getRegionId()) ) {
            cache = Cache.getInstance(getProvider(), "networkOfferings", NetworkOffering.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Hour>(1, TimePeriod.HOUR));

            Collection<NetworkOffering> offerings = ( Collection<NetworkOffering> ) cache.get(getContext());

            if( offerings != null ) {
                return offerings;
            }
        }
        Document doc = new CSMethod(getProvider()).get(LIST_NETWORK_OFFERINGS, new Param("zoneId", regionId));
        NodeList matches = doc.getElementsByTagName("networkoffering");
        final List<NetworkOffering> offerings = new ArrayList<NetworkOffering>();

        for( int i = 0; i < matches.getLength(); i++ ) {
            Node node = matches.item(i);
            NodeList attributes = node.getChildNodes();
            NetworkOffering offering = new NetworkOffering();

            for( int j = 0; j < attributes.getLength(); j++ ) {
                Node n = attributes.item(j);
                String value;

                if( n.getChildNodes().getLength() > 0 ) {
                    value = n.getFirstChild().getNodeValue();
                }
                else {
                    value = null;
                }
                if( n.getNodeName().equals("id") && value != null ) {
                    offering.offeringId = value.trim();
                }
                else if( n.getNodeName().equalsIgnoreCase("availability") ) {
                    offering.availability = ( value == null ? "unavailable" : value.trim() );
                }
                else if( n.getNodeName().equalsIgnoreCase("guestiptype") ) {
                    offering.networkType = ( value == null ? "direct" : value.trim() );
                }
            }
            offerings.add(offering);
        }
        if( cache != null ) {
            cache.put(getContext(), offerings);
        }
        return offerings;
    }

    private @Nullable String getNetworkOffering( @Nonnull String regionId ) throws InternalException, CloudException {
        for( NetworkOffering offering : getNetworkOfferings(regionId) ) {
            if( !offering.availability.equalsIgnoreCase("unavailable") && offering.networkType.equals("Isolated") ) {
                return offering.offeringId;
            }
        }
        return null;
    }

    @Override
    public @Nullable VLAN getVlan( @Nonnull String vlanId ) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VLAN.getVlan");
        try {
            try {
                Document doc = new CSMethod(getProvider()).get(Network.LIST_NETWORKS, new Param("zoneId", getContext().getRegionId()), new Param("id", vlanId));
                NodeList matches = doc.getElementsByTagName("network");

                for( int i = 0; i < matches.getLength(); i++ ) {
                    Node node = matches.item(i);

                    if( node != null ) {
                        VLAN vlan = toNetwork(node);

                        if( vlan != null ) {
                            return vlan;
                        }
                    }
                }
                return null;
            }
            catch( CSException e ) {
                if( e.getHttpCode() == 431 ) {
                    return null;
                }
                throw e;
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Nullable @Override
    public String getAttachedInternetGatewayId( @Nonnull String vlanId ) throws CloudException, InternalException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Nullable @Override
    public InternetGateway getInternetGatewayById( @Nonnull String gatewayId ) throws CloudException, InternalException {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VLAN.isSubscribed");
        try {
            CSMethod method = new CSMethod(getProvider());

            try {
                method.get(Network.LIST_NETWORKS, new Param("zoneId", getContext().getRegionId()));
                return true;
            }
            catch( CSException e ) {
                int code = e.getHttpCode();

                if( code == HttpServletResponse.SC_FORBIDDEN || code == 401 || code == 531 ) {
                    return false;
                }
                throw e;
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<VLAN> listVlans() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VLAN.listVlans");
        try {
            CSMethod method = new CSMethod(getProvider());
            Document doc = method.get(Network.LIST_NETWORKS, new Param("zoneId", getContext().getRegionId()), new Param("canusefordeploy", "true"));
            List<VLAN> networks = new ArrayList<VLAN>();

            int numPages = 1;
            NodeList nodes = doc.getElementsByTagName("count");
            Node n = nodes.item(0);
            if( n != null ) {
                String value = n.getFirstChild().getNodeValue().trim();
                int count = Integer.parseInt(value);
                numPages = count / 500;
                int remainder = count % 500;
                if( remainder > 0 ) {
                    numPages++;
                }
            }

            for( int page = 1; page <= numPages; page++ ) {
                if( page > 1 ) {
                    String nextPage = String.valueOf(page);
                    doc = method.get(LIST_NETWORKS,
                            new Param("zoneId", getContext().getRegionId()),
                            new Param("pagesize", "500"),
                            new Param("page", nextPage),
                            new Param("canusefordeploy", "true")
                    );
                }
                NodeList matches = doc.getElementsByTagName("network");

                for( int i = 0; i < matches.getLength(); i++ ) {
                    Node node = matches.item(i);

                    if( node != null ) {
                        VLAN vlan = toNetwork(node);

                        if( vlan != null ) {
                            networks.add(vlan);
                        }
                    }
                }
            }
            return networks;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void removeInternetGatewayById( @Nonnull String id ) throws CloudException, InternalException {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    public @Nonnull Iterable<VLAN> listDefaultNetworks(boolean shared, boolean forDeploy) throws CloudException, InternalException {
        CSMethod method = new CSMethod(getProvider());
        List<Param> params = new ArrayList<Param>();

        params.add(new Param("zoneId", getContext().getRegionId()));
        if( forDeploy ) {
            params.add(new Param("canUseForDeploy", "true"));
        }
        if( !shared ) {
            params.add(new Param("account", getContext().getAccountNumber()));
            // filtering by account only works with the domain now
            params.add(new Param("domainid", getProvider().getDomainId()));
        }
        Document doc = method.get(Network.LIST_NETWORKS, params);
        List<VLAN> networks = new ArrayList<VLAN>();
        NodeList matches = doc.getElementsByTagName("network");

        for( int i = 0; i < matches.getLength(); i++ ) {
            Node node = matches.item(i);

            if( node != null ) {
                VLAN vlan = toNetwork(node);

                if( vlan != null ) {
                    if( vlan.getTag("displaynetwork") == null || vlan.getTag("displaynetwork").equals("true") ) {
                        if( vlan.getTag("isdefault") == null || vlan.getTag("isdefault").equals("true") ) {
                            networks.add(vlan);
                        }
                    }
                }
            }
        }
        return networks;
    }

    @Override
    public @Nonnull VLAN createVlan( @Nonnull String cidr, @Nonnull String name, @Nonnull String description, @Nullable String domainName, @Nullable String[] dnsServers, @Nullable String[] ntpServers ) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VLAN.createVlan");
        try {
            if( !getCapabilities().allowsNewVlanCreation() ) {
                throw new OperationNotSupportedException();
            }

            String regionId = getContext().getRegionId();
            if( regionId == null ) {
                throw new GeneralCloudException("No region was set for this request", CloudErrorType.GENERAL);
            }

            String offering = getNetworkOffering(regionId);
            if( offering == null ) {
                throw new GeneralCloudException("No offerings exist for " + getContext().getRegionId(), CloudErrorType.GENERAL);
            }

            List<Param> params = new ArrayList<Param>();
            params.add(new Param("zoneId", getContext().getRegionId()));
            params.add(new Param("networkOfferingId", offering));
            params.add(new Param("name", name));
            params.add(new Param("displayText", name));

            String[] parts = cidr.split("/");
            String netmask = "255.255.255.255";
            if( parts.length > 0 ) {
                params.add(new Param("gateway", parts[0]));
            }

            if( parts.length >= 1 ) {
                netmask = parts[1];
                int prefix = Integer.parseInt(netmask);
                int mask = 0xffffffff << ( 32 - prefix );

                int value = mask;
                byte[] bytes = new byte[]{( byte ) ( value >>> 24 ), ( byte ) ( value >> 16 & 0xff ), ( byte ) ( value >> 8 & 0xff ), ( byte ) ( value & 0xff )};

                try {
                    InetAddress netAddr = InetAddress.getByAddress(bytes);
                    netmask = netAddr.getHostAddress();
                }
                catch( UnknownHostException e ) {
                    throw new InternalException("Unable to parse netmask from " + cidr);
                }
            }
            params.add(new Param("netmask", netmask));

            CSMethod method = new CSMethod(getProvider());
            final Document doc = method.get(CREATE_NETWORK, params);
            NodeList matches = doc.getElementsByTagName("network");

            for( int i = 0; i < matches.getLength(); i++ ) {
                Node node = matches.item(i);

                if( node != null ) {
                    VLAN network = toNetwork(node);

                    if( network != null ) {
                        // create default egress rule
                        try {
                            method.get(CREATE_EGRESS_RULE, new Param("protocol", "All"), new Param("cidrlist", "0.0.0.0/0"), new Param("networkid", network.getProviderVlanId()));
                        }
                        catch( Throwable ignore ) {
                            logger.warn("Unable to create default egress rule");
                        }
                        
                        // Set tags
                        List<Tag> tags = new ArrayList<Tag>();
                        tags.add(new Tag("Name", name));
                        tags.add(new Tag("Description", description));
                        getProvider().createTags(new String[] { network.getProviderVlanId() }, "Network", tags.toArray(new Tag[tags.size()]));
                        return network;
                    }
                }
            }
            throw new GeneralCloudException("Creation requested failed to create a network without an error", CloudErrorType.GENERAL);
        }
        finally {
            APITrace.end();
        }
    }

    private @Nullable VLAN toNetwork( @Nullable Node node ) throws CloudException, InternalException {
        if( node == null ) {
            return null;
        }
        VLAN network = new VLAN();

        String netmask = null;
        String gateway = null;

        NodeList attributes = node.getChildNodes();

        network.setProviderOwnerId(getContext().getAccountNumber());
        network.setProviderRegionId(getContext().getRegionId());
        network.setCurrentState(VLANState.AVAILABLE);
        network.setSupportedTraffic(new IPVersion[]{IPVersion.IPV4});
        for( int i = 0; i < attributes.getLength(); i++ ) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName().toLowerCase();
            String value;

            if( attribute.getChildNodes().getLength() > 0 ) {
                value = attribute.getFirstChild().getNodeValue();
            }
            else {
                value = null;
            }
            if( name.equalsIgnoreCase("id") ) {
                network.setProviderVlanId(value);
            }
            else if( name.equalsIgnoreCase("name") ) {
                if( network.getName() == null ) {
                    network.setName(value);
                }
            }
            else if( name.equalsIgnoreCase("displaytext") ) {
                network.setName(value);
            }
            else if( name.equalsIgnoreCase("displaynetwork") ) {
                network.setTag("displaynetwork", value);
            }
            else if( name.equalsIgnoreCase("isdefault") ) {
                network.setTag("isdefault", value);
            }
            else if( name.equalsIgnoreCase("networkdomain") ) {
                network.setDomainName(value);
            }
            else if( name.equalsIgnoreCase("zoneid") && value != null ) {
                network.setProviderRegionId(value);
            }
            else if( name.startsWith("dns") && value != null && !value.trim().equals("") ) {
                String[] dns;

                if( network.getDnsServers() != null ) {
                    dns = new String[network.getDnsServers().length + 1];
                    for( int idx = 0; idx < network.getDnsServers().length; idx++ ) {
                        dns[idx] = network.getDnsServers()[idx];
                    }
                    dns[dns.length - 1] = value;
                }
                else {
                    dns = new String[]{value};
                }
                network.setDnsServers(dns);
            }
            else if( name.equalsIgnoreCase("netmask") ) {
                netmask = value;
            }
            else if( name.equals("gateway") ) {
                gateway = value;
            }
            else if( name.equalsIgnoreCase("networkofferingdisplaytext") ) {
                network.setNetworkType(value);
            }
            else if( name.equalsIgnoreCase("account") ) {
                network.setProviderOwnerId(value);
            }
        }
        if( network.getProviderVlanId() == null ) {
            return null;
        }
        network.setProviderDataCenterId(network.getProviderRegionId());
        if( network.getName() == null ) {
            network.setName(network.getProviderVlanId());
        }
        if( network.getDescription() == null ) {
            network.setDescription(network.getName());
        }
        if( gateway != null ) {
            if( netmask == null ) {
                netmask = "255.255.255.0";
            }
            network.setCidr(netmask, gateway);
        }
        return network;
    }

    @Nonnull @Override
    public Collection<InternetGateway> listInternetGateways( @Nullable String vlanId ) throws CloudException, InternalException {
        return Collections.emptyList();
    }

    @Override
    public @Nonnull Iterable<Networkable> listResources( @Nonnull String inVlanId ) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VLAN.listResources");
        try {
            ArrayList<Networkable> resources = new ArrayList<Networkable>();
            NetworkServices network = getProvider().getNetworkServices();

            FirewallSupport fwSupport = network.getFirewallSupport();

            if( fwSupport != null ) {
                for( Firewall fw : fwSupport.list() ) {
                    if( inVlanId.equals(fw.getProviderVlanId()) ) {
                        resources.add(fw);
                    }
                }
            }

            IpAddressSupport ipSupport = network.getIpAddressSupport();

            if( ipSupport != null ) {
                for( IPVersion version : ipSupport.getCapabilities().listSupportedIPVersions() ) {
                    for( org.dasein.cloud.network.IpAddress addr : ipSupport.listIpPool(version, false) ) {
                        if( inVlanId.equals(addr.getProviderVlanId()) ) {
                            resources.add(addr);
                        }
                    }

                }
            }
            for( RoutingTable table : listRoutingTablesForVlan(inVlanId) ) {
                resources.add(table);
            }
            Iterable<VirtualMachine> vms = getProvider().getComputeServices().getVirtualMachineSupport().listVirtualMachines();

            for( VirtualMachine vm : vms ) {
                if( inVlanId.equals(vm.getProviderVlanId()) ) {
                    resources.add(vm);
                }
            }
            return resources;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<ResourceStatus> listVlanStatus() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VLAN.listVlanStatus");
        try {
            CSMethod method = new CSMethod(getProvider());
            Document doc = method.get(
                    Network.LIST_NETWORKS,
                    new Param("zoneId", getContext().getRegionId()),
                    new Param("canusefordeploy", "true")
            );
            List<ResourceStatus> networks = new ArrayList<ResourceStatus>();

            int numPages = 1;
            NodeList nodes = doc.getElementsByTagName("count");
            Node n = nodes.item(0);
            if( n != null ) {
                String value = n.getFirstChild().getNodeValue().trim();
                int count = Integer.parseInt(value);
                numPages = count / 500;
                int remainder = count % 500;
                if( remainder > 0 ) {
                    numPages++;
                }
            }

            for( int page = 1; page <= numPages; page++ ) {
                if( page > 1 ) {
                    String nextPage = String.valueOf(page);
                    doc = method.get(
                            LIST_NETWORKS,
                            new Param("zoneId", getContext().getRegionId()),
                            new Param("pagesize", "500"),
                            new Param("page", nextPage),
                            new Param("canusefordeploy", "true")
                    );
                }
                NodeList matches = doc.getElementsByTagName("network");
                for( int i = 0; i < matches.getLength(); i++ ) {
                    Node node = matches.item(i);
                    if( node != null ) {
                        ResourceStatus vlan = toVLANStatus(node);
                        if( vlan != null ) {
                            networks.add(vlan);
                        }
                    }
                }
            }
            return networks;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void removeVlan( String vlanId ) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VLAN.removeVlan");
        try {
            Document doc = new CSMethod(getProvider()).get(DELETE_NETWORK, new Param("id", vlanId));
            getProvider().waitForJob(doc, "Delete VLAN");
        }
        finally {
            APITrace.end();
        }
    }

    public @Nullable ResourceStatus toVLANStatus( @Nullable Node node ) {
        if( node == null ) {
            return null;
        }
        NodeList attributes = node.getChildNodes();
        String networkId = null;

        for( int i = 0; i < attributes.getLength(); i++ ) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName().toLowerCase();
            String value;

            if( attribute.getChildNodes().getLength() > 0 ) {
                value = attribute.getFirstChild().getNodeValue();
            }
            else {
                value = null;
            }
            if( name.equalsIgnoreCase("id") ) {
                networkId = value;
                break;
            }
        }
        if( networkId == null ) {
            return null;
        }
        return new ResourceStatus(networkId, VLANState.AVAILABLE);
    }

    @Override
    public void updateVLANTags(@Nonnull String vlanId, @Nonnull Tag... tags) throws CloudException, InternalException {
    	updateVLANTags(new String[] { vlanId }, tags);
    }
    
    @Override
    public void updateVLANTags(@Nonnull String[] vlanIds, @Nonnull Tag... tags) throws CloudException, InternalException {
    	APITrace.begin(getProvider(), "VLAN.updateTags");
    	try {
    		getProvider().updateTags(vlanIds, "Network", tags);
    	}
    	finally {
    		APITrace.end();
    	}
    }
    
    @Override
    public void removeVLANTags(@Nonnull String vlanId, @Nonnull Tag... tags) throws CloudException, InternalException {
    	removeVLANTags(new String[] { vlanId }, tags);
    }
    
    @Override
    public void removeVLANTags(@Nonnull String[] vlanIds, @Nonnull Tag... tags) throws CloudException, InternalException {
    	APITrace.begin(getProvider(), "VLAN.removeTags");
    	try {
    		getProvider().removeTags(vlanIds, "Network", tags);
    	}
    	finally {
    		APITrace.end();
    	}
    }

    @Override
    public void setVLANTags(@Nonnull String vlanId, @Nonnull Tag... tags) throws CloudException, InternalException {
    	setVLANTags(new String[] { vlanId }, tags);
    }

    @Override
    public void setVLANTags(@Nonnull String[] vlanIds, @Nonnull Tag... tags) throws CloudException, InternalException {
    	APITrace.begin(getProvider(), "VLAN.setTags");
    	try {
    		removeVLANTags(vlanIds);
    		getProvider().createTags(vlanIds, "Network", tags);
    	}
    	finally {
    		APITrace.end();
    	}
    }
}
