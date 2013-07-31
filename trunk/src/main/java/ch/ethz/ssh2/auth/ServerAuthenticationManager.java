
package ch.ethz.ssh2.auth;

import java.io.IOException;

import ch.ethz.ssh2.ServerAuthenticationCallback;
import ch.ethz.ssh2.channel.ChannelManager;
import ch.ethz.ssh2.packets.PacketServiceAccept;
import ch.ethz.ssh2.packets.PacketServiceRequest;
import ch.ethz.ssh2.packets.PacketUserauthBanner;
import ch.ethz.ssh2.packets.PacketUserauthFailure;
import ch.ethz.ssh2.packets.PacketUserauthSuccess;
import ch.ethz.ssh2.packets.Packets;
import ch.ethz.ssh2.packets.TypesReader;
import ch.ethz.ssh2.server.ServerConnectionState;
import ch.ethz.ssh2.transport.MessageHandler;

public class ServerAuthenticationManager implements MessageHandler
{
	private final ServerConnectionState state;

	public ServerAuthenticationManager(ServerConnectionState state)
	{
		this.state = state;
		state.tm.registerMessageHandler(this, 0, 255);
	}

	private void success() throws IOException
	{
		PacketUserauthSuccess pus = new PacketUserauthSuccess();
		state.tm.sendAsynchronousMessage(pus.getPayload());

		state.tm.removeMessageHandler(this, 0, 255);
		state.tm.registerMessageHandler(this, 50, 79);

		state.cm = new ChannelManager(state);

		state.flag_auth_completed = true;
	}

	private void fail() throws IOException
	{
		ServerAuthenticationCallback cb = state.cb_auth;

		String[] remaining_methods = null;

		if (cb != null)
			remaining_methods = cb.getRemainingAuthMethods(state.conn);

		if (remaining_methods == null)
			remaining_methods = new String[0];

		PacketUserauthFailure puf = new PacketUserauthFailure(remaining_methods, false);
		state.tm.sendAsynchronousMessage(puf.getPayload());
	}

	public void handleMessage(byte[] msg, int msglen) throws IOException
	{
		synchronized (state)
		{
		}

		/* Ignore all authentication messages after successful auth */

		if (state.flag_auth_completed)
			return;

		if (!state.flag_auth_serviceRequested)
		{
			/* Must be PacketServiceRequest */

			PacketServiceRequest psr = new PacketServiceRequest(msg, 0, msglen);

			if (!"ssh-userauth".equals(psr.getServiceName()))
				throw new IOException("SSH protocol error, expected ssh-userauth service request");

			PacketServiceAccept psa = new PacketServiceAccept("ssh-userauth");
			state.tm.sendAsynchronousMessage(psa.getPayload());

			String banner = state.cb_auth.initAuthentication(state.conn);

			if (banner != null)
			{
				PacketUserauthBanner pub = new PacketUserauthBanner(banner, "en");
				state.tm.sendAsynchronousMessage(pub.getPayload());
			}

			state.flag_auth_serviceRequested = true;

			return;
		}

		ServerAuthenticationCallback cb = state.cb_auth;

		TypesReader tr = new TypesReader(msg, 0, msglen);
		int packet_type = tr.readByte();

		if (packet_type == Packets.SSH_MSG_USERAUTH_REQUEST)
		{
			String username = tr.readString("UTF-8");
			String service = tr.readString();
			String method = tr.readString();

			if (!"ssh-connection".equals(service))
			{
				fail();
				return;
			}

			if ("none".equals(method))
			{
				if ((cb != null) && (cb.authenticateWithNone(state.conn, username)))
				{
					success();
					return;
				}
			}
			
			if ("password".equals(method))
			{
				boolean flag_change_pass = tr.readBoolean();
				
				if (flag_change_pass)
				{
					fail();
					return;
				}
				
				String password = tr.readString("UTF-8");
				
				if ((cb != null) && (cb.authenticateWithPassword(state.conn, username, password)))
				{
					success();
					return;
				}
			}
			
			fail();
			return;
		}

		throw new IOException("Unexpected authentication packet " + packet_type);
	}
}