package pbftSimulator.replica;

import pbftSimulator.Client;
import pbftSimulator.Simulator;
import pbftSimulator.message.Message;
import pbftSimulator.message.PrePrepareMsg;
import pbftSimulator.message.ReplyMsg;
import pbftSimulator.message.RequestMsg;

import java.util.Set;

public class ByztReplica extends Replica{
	
	public static final String BYZTPROCESSTAG = "ByztProcess";
	
	public static final String BYZTRECEIVETAG = "ByztReceive";
	
	public static final String BYZTSENDTAG = "ByztSend";
	
	public ByztReplica(int id, int[] netDlys, int[]netDlysToClis) {
		super(id, netDlys, netDlysToClis);
		receiveTag = BYZTRECEIVETAG;
		sendTag = BYZTSENDTAG;
	}
}