package pbftSimulator.scheduler;

public class MessageCrash {
    public int senderId;
    public int receiverId;
    public int messageType;
    //PRE-PREPARE = 1;
    //PREPARE = 2;
    //COMMIT = 3;
    //REPLY = 4;
    //CHECKPOINT = 5;
    //VIEW-CHANGE = 6;
    //NEW-VIEW = 7;
    //TIMEOUT = 8;					//用来提醒节点超时的虚拟消息
    //CLIENT-TIMEOUT = 9;					//用来提醒客户端超时的虚拟消息

    public MessageCrash(int senderId,int receiverId,int messageType){
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.messageType = messageType;
    }

    @Override
    public String toString() {
        return "MessageCrash{" +
                "senderId=" + senderId +
                ", receiverId=" + receiverId +
                ", messageType=" + messageType +
                '}';
    }
}
