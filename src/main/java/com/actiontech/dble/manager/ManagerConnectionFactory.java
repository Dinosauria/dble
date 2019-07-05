/*
* Copyright (C) 2016-2019 ActionTech.
* based on code by MyCATCopyrightHolder Copyright (c) 2013, OpenCloudDB/MyCAT.
* License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
*/
package com.actiontech.dble.manager;

import com.actiontech.dble.net.FrontendConnection;
import com.actiontech.dble.net.factory.FrontendConnectionFactory;
import com.actiontech.dble.net.handler.ManagerAuthenticator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

import java.io.IOException;
import java.nio.channels.NetworkChannel;

//import MycatPrivileges;

/**
 * @author mycat
 */
public class ManagerConnectionFactory extends FrontendConnectionFactory {

    @Override
    protected FrontendConnection getConnection(NetworkChannel channel) throws IOException {
        ManagerConnection c = new ManagerConnection(channel);
        c.setSocketParams(true);
        c.setPrivileges(ManagerPrivileges.instance());
        c.setHandler(new ManagerAuthenticator(c));
        c.setQueryHandler(new ManagerQueryHandler(c));
        return c;
    }


    @Override
    protected FrontendConnection getConnection(ChannelPipeline channelPipeline) throws IOException {
        ManagerConnection c = new ManagerConnection(channelPipeline);
        c.setSocketParams(true);
        c.setHandler(new ManagerAuthenticator(c));
        c.setPrivileges(ManagerPrivileges.instance());
        c.setQueryHandler(new ManagerQueryHandler(c));
        return c;
    }

}
