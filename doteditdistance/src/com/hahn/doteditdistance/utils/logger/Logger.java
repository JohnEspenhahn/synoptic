package com.hahn.doteditdistance.utils.logger;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;

import org.github.com.jvec.JVec;

import com.espenhahn.jformatter.JFormatter;
import com.espenhahn.jformatter.map.JSortedMapFormatter;
import com.espenhahn.jformatter.socket.SocketPortFormatter;

public class Logger {
	
	public static final String REGISTER_CHANNEL_LOG = "connect";
	private JFormatter<? super SortedMap<?,?>> map_formatter = new JSortedMapFormatter<SortedMap<?,?>>();
	private JFormatter<SocketAddress> address_formatter = new SocketPortFormatter();
	
	private Map<String,String> channels;
	private JVec vcInfo;
	
	private Logger() {
		this.channels = new HashMap<String,String>();
	}
	
	public String getProcessName() {
		if (vcInfo == null) return "";
		
		return vcInfo.getPid();
	}

	public void enable(String processName, String...allProcessNames) {
		if (vcInfo == null) {
			vcInfo = new JVec(processName, map_formatter, processName, allProcessNames);
			vcInfo.enableLogging(); // Format as a sorted map (key excluded)
			vcInfo.setWarnDynamicJoin(); // Warn if dynamic join b/c we're using sorted display (key excluded)		
		}
	}
	
	public void logLocalEvent(String log) {
		if (vcInfo != null) {
			try {
				JVec.writeGlobalLogMsg(vcInfo.getLogName(), "# " + log);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public void registerChannel(SocketChannel channel, String otherProcessName) {
		if (vcInfo == null) return;
		
		String channelId = null;
		try {
			// Track outgoing channel
			channelId = String.format("%s_%s", 
					address_formatter.format(channel.getLocalAddress()), 
					address_formatter.format(channel.getRemoteAddress()));
			
			if (otherProcessName != null) {
				channels.put(channelId, otherProcessName);
				logLocalEvent(String.format("%s %s %s", REGISTER_CHANNEL_LOG, channelId, otherProcessName));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public String getChannelString() {
		StringBuilder b = new StringBuilder();
		for (Entry<String,String> e: channels.entrySet()) {
			appendChannelString(b, e.getKey(), getProcessName(), e.getValue());
			
			String flipped = flipChannelId(e.getKey());
			if (!channels.containsKey(flipped))
				appendChannelString(b, flipped, e.getValue(), getProcessName());
		}
		
		return b.toString();
	}
	
	public static void appendChannelString(StringBuilder b, String channel_name, String src, String dest) {
		b.append(channel_name);
		b.append(":");
		b.append(src);
		b.append("->");
		b.append(dest);
		b.append(";");
	}
	
	private ByteBuffer wrap(String log, ByteBuffer bb) throws IOException {
		if (vcInfo == null) return bb;
		
		return wrapBytes(vcInfo.prepareSend(log, getBytes(bb)));
	}
	
	public ByteBuffer prepareSend(SocketChannel channel, ByteBuffer bb) throws IOException {
		return wrap(String.format("%s_%s!", 
				address_formatter.format(channel.getLocalAddress()), 
				address_formatter.format(channel.getRemoteAddress())), bb);
	}
	
	private ByteBuffer unwrap(String log, ByteBuffer bb) throws IOException {
		if (vcInfo == null) return bb;
		
		bb.flip();
		byte[] unwrapped = vcInfo.unpackReceive(log, getBytes(bb));
		bb.put(unwrapped);
		bb.flip();
		
		return bb;
	}
	
	public ByteBuffer prepareReceive(SocketChannel channel, ByteBuffer bb) throws IOException {
		return unwrap(String.format("%s_%s?", 
				address_formatter.format(channel.getRemoteAddress()), 
				address_formatter.format(channel.getLocalAddress())), bb);
	}
	
	public static String flipChannelId(String channelId) {
		String[] parts = channelId.split("_");
		if (parts.length != 2) throw new RuntimeException("Invalid channel id '" + channelId + "'");
		
		return parts[1] + "_" + parts[0];
	}
	
	private byte[] getBytes(ByteBuffer bb) {
		byte[] bytes = new byte[bb.remaining()];
		bb.get(bytes);
		bb.flip();
		
		return bytes;
	}
	
	private ByteBuffer wrapBytes(byte[] bytes) {
		return ByteBuffer.wrap(bytes);
	}
	
	private static Logger instance;
	public static Logger get() {
		if (instance == null)
			instance = new Logger();
		
		return instance;
	}
	
}
