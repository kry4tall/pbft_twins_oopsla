package pbftSimulator.replica;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import pbftSimulator.Client;
import pbftSimulator.Simulator;
import pbftSimulator.Utils;
import pbftSimulator.message.CheckPointMsg;
import pbftSimulator.message.CommitMsg;
import pbftSimulator.message.LastReply;
import pbftSimulator.message.Message;
import pbftSimulator.message.NewViewMsg;
import pbftSimulator.message.PrePrepareMsg;
import pbftSimulator.message.PrepareMsg;
import pbftSimulator.message.ReplyMsg;
import pbftSimulator.message.RequestMsg;
import pbftSimulator.message.TimeOutMsg;
import pbftSimulator.message.ViewChangeMsg;

public class Replica {

    public static final int K = 10;                        //发送checkpoint消息的周期

    public static final int L = 30;                        //L = 高水位 - 低水位		(一般取L>=K*2)

    public static final int PROCESSING = 0;        //没有收到f+1个reply

    public static final int STABLE = 1;            //已经收到了f+1个reply

    public String receiveTag = "Receive";

    public String sendTag = "Send";

    public int id;                                        //当前节点的id

    public long timestamp; //当前时间戳

    public int v;                                        //视图编号

    public int n;                                        //消息处理序列号

    public int lastRepNum;                                //最新回复的消息处理序列号

    public int h;                                        //低水位 = 稳定状态checkpoint的n

    public int[] netDlys;                                //与其他节点的网络延迟

    public int[] netDlyToClis;                            //与客户端的网络延迟

    public boolean isTimeOut;                            //当前正在处理的请求是否超时（如果超时了不会再发送任何消息）

    //消息缓存<type, <msg>>:type消息类型;
    public Map<Integer, Set<Message>> msgCache;

    //最新reply的状态集合<c, <c, t, r>>:c客户端编号;t请求消息时间戳;r返回结果
    public Map<Integer, LastReply> lastReplyMap;

    //checkpoints集合<n, <c, <c, t, r>>>:n消息处理序列号
    public Map<Integer, Map<Integer, LastReply>> checkPoints;

    public Map<Message, Integer> reqStats;            //request请求状态

    public static Comparator<PrePrepareMsg> nCmp = new Comparator<PrePrepareMsg>() {
        @Override
        public int compare(PrePrepareMsg c1, PrePrepareMsg c2) {
            return c1.n - c2.n;
        }
    };

    public Replica(int id, int[] netDlys, int[] netDlyToClis) {
        this.id = id;
        this.netDlys = netDlys;
        this.netDlyToClis = netDlyToClis;
        msgCache = new HashMap<>();
        lastReplyMap = new HashMap<>();
        checkPoints = new HashMap<>();
        reqStats = new HashMap<>();
        checkPoints.put(0, lastReplyMap);
        //初始时启动Timer
        setTimer(lastRepNum + 1, 0);
    }

    public void msgProcess(Message msg) {
        timestamp = msg.receiveTime;
        msg.print(receiveTag);
        switch (msg.type) {
            case Message.REQUEST:
                //主节点广播pre-prepare
                receiveRequest(msg);
                break;
            case Message.PREPREPARE:
                receivePreprepare(msg);
                break;
            case Message.PREPARE:
                receivePrepare(msg);
                break;
            case Message.COMMIT:
                receiveCommit(msg);
                break;
            case Message.VIEWCHANGE:
                receiveViewChange(msg);
                break;
            case Message.NEWVIEW:
                receiveNewView(msg);
                break;
            case Message.TIMEOUT:
                receiveTimeOut(msg);
                break;
            case Message.CHECKPOINT:
                receiveCheckPoint(msg);
                break;
            default:
                System.out.println("##Error##消息类型错误！");
                return;
        }
        //收集所有符合条件的prePrepare消息,并进行后续处理
        Set<Message> prePrepareMsgSet = msgCache.get(Message.PREPREPARE);
        Queue<PrePrepareMsg> executeQ = new PriorityQueue<>(nCmp);
        if (prePrepareMsgSet == null) return;
        for (Message m : prePrepareMsgSet) {
            PrePrepareMsg mm = (PrePrepareMsg) m;
            if (mm.v >= v && mm.n >= lastRepNum + 1) {
                tryToCommit(m, msg.receiveTime);
                executeQ.add(mm);
            }
        }
        while (!executeQ.isEmpty()) {
            tryToReply(executeQ.poll(), msg.receiveTime);
        }
        //垃圾处理
        garbageCollect();
    }

    public void tryToCommit(Message msg, long time) {
        PrePrepareMsg mm = (PrePrepareMsg) msg;
        String d = Utils.getMD5Digest(mm.mString());
        CommitMsg cm = new CommitMsg(mm.proposalHash, mm.v, mm.n, d, id, id, id, time);
        //发过了或者还没有prepared
        if (isInMsgCache(cm) || !prepared(mm)) {
            return;
        }
        //否则发
        cm.sendTime = timestamp;
        Simulator.sendMsgToOthers(cm, id, sendTag);
        addMessageToCache(cm);
    }

    //msg类型只可能是pre-prepare
    public void tryToReply(Message msg, long time) {
        PrePrepareMsg prePrepareMsg = (PrePrepareMsg) msg;
        RequestMsg requestMsg = null;
        ReplyMsg replyMsg = null;
        if (prePrepareMsg.m != null) {
            requestMsg = (RequestMsg) (prePrepareMsg.m);
            replyMsg = new ReplyMsg(prePrepareMsg.proposalHash, prePrepareMsg.v, requestMsg.t, requestMsg.c, id, "result", id, requestMsg.c, time + netDlyToClis[Client.getCliArrayIndex(requestMsg.c)]);
        }

        //判断request是否执行完毕:
        // 1.没有request或cache里没有reply
        // 2.请求是最新的
        // 3.请求已经committed
        if ((requestMsg == null || !isInMsgCache(replyMsg)) && prePrepareMsg.n == lastRepNum + 1 && commited(prePrepareMsg)) {
            lastRepNum++;
            setTimer(lastRepNum + 1, time);
            if (requestMsg != null) {
                replyMsg.sendTime = timestamp;
                Simulator.sendMsg(replyMsg, sendTag);
                LastReply llp = lastReplyMap.get(requestMsg.c);
                if (llp == null) {
                    llp = new LastReply(requestMsg.c, requestMsg.t, "result");
                    lastReplyMap.put(requestMsg.c, llp);
                }
                llp.t = requestMsg.t;
                reqStats.put(requestMsg, STABLE);

            }
            //周期性发送checkpoint消息
            if (prePrepareMsg.n % K == 0) {
                Message checkptMsg = new CheckPointMsg(v, prePrepareMsg.n, lastReplyMap, id, id, id, time);
                addMessageToCache(checkptMsg);
                checkptMsg.sendTime = timestamp;
                Simulator.sendMsgToOthers(checkptMsg, id, sendTag);
            }
        }
    }

    public boolean prepared(PrePrepareMsg ppm) {
        Set<Message> prepareMsgSet = msgCache.get(Message.PREPARE);
        if (prepareMsgSet == null) return false;
        int cnt = 0;
        String d = Utils.getMD5Digest(ppm.mString());
        for (Message msg : prepareMsgSet) {
            PrepareMsg pm = (PrepareMsg) msg;
            if (pm.v == ppm.v && pm.n == ppm.n && pm.d.equals(d) && pm.proposalHash.equals(ppm.proposalHash)) {
                cnt++;
            }
        }
        //如果一个节点收到的2f（f为可容忍的拜占庭节点数）个其它节点发来的摘要都和自己相等，就向全网广播一条commit消息。
        return cnt >= 2 * Utils.getMaxTorelentNumber(Simulator.RN);
    }

    public boolean commited(PrePrepareMsg ppm) {
        Set<Message> commitMsgSet = msgCache.get(Message.COMMIT);
        if (commitMsgSet == null) return false;
        int cnt = 0;
        String d = Utils.getMD5Digest(ppm.mString());
        for (Message msg : commitMsgSet) {
            CommitMsg cm = (CommitMsg) msg;
            if (cm.v == ppm.v && cm.n == ppm.n && cm.d.equals(d) && cm.proposalHash.equals(ppm.proposalHash)) {
                cnt++;
            }
        }
        //如果一个节点收到2f+1条commit消息，即可提交新区块及其交易到本地的区块链和状态数据库。
        return cnt > 2 * Utils.getMaxTorelentNumber(Simulator.RN);
    }

    public boolean viewChanged(ViewChangeMsg m) {
        Set<Message> viewChangeMsgSet = msgCache.get(Message.VIEWCHANGE);
        if (viewChangeMsgSet == null) return false;
        int cnt = 0;
        for (Message msg : viewChangeMsgSet) {
            ViewChangeMsg vm = (ViewChangeMsg) msg;
            if (vm.v == m.v && vm.sn == m.sn) {
                cnt++;
            }
        }
        return cnt > 2 * Utils.getMaxTorelentNumber(Simulator.RN);
    }

    public void garbageCollect() {
        Set<Message> checkptMsgSet = msgCache.get(Message.CHECKPOINT);
        if (checkptMsgSet == null) return;
        //找出满足f+1条件的最大的sn
        Map<Integer, Integer> snMap = new HashMap<>();
        int maxN = 0;
        for (Message msg : checkptMsgSet) {
            CheckPointMsg ckt = (CheckPointMsg) msg;
            if (!snMap.containsKey(ckt.n)) {
                snMap.put(ckt.n, 0);
            }
            int cnt = snMap.get(ckt.n) + 1;
            snMap.put(ckt.n, cnt);
            if (cnt > Utils.getMaxTorelentNumber(Simulator.RN)) {
                checkPoints.put(ckt.n, ckt.s);
                maxN = Math.max(maxN, ckt.n);
            }
        }
        //删除msgCache和checkPoints中小于n的所有数据，以及更新h值为sn
        deleteCache(maxN);
        deleteCheckPts(maxN);
        h = maxN;
//		System.out.println(id+"[水位]"+h+"-"+(h+L));
    }

    public void receiveRequest(Message msg) {
        if (msg == null)
            return;
        RequestMsg requestMsg = (RequestMsg) msg;
        int c = requestMsg.c;
        long t = requestMsg.t;
        //如果这条请求已经reply过了，那么就再回复一次reply
        if (reqStats.containsKey(msg) && reqStats.get(msg) == STABLE) {
            long receiveTime = msg.receiveTime + netDlyToClis[Client.getCliArrayIndex(c)];
            Message replyMsg = new ReplyMsg(msg.proposalHash, v, t, c, id, "result", id, c, receiveTime);
            replyMsg.sendTime = timestamp;
            Simulator.sendMsg(replyMsg, sendTag);
            return;
        }
        if (!reqStats.containsKey(msg)) {
            //把消息放进缓存
            addMessageToCache(msg);
            reqStats.put(msg, PROCESSING);
        }
        //如果是主节点
        if (Simulator.IsPrimaryNodeId(id)) {
            //如果已经发送过PrePrepare消息，那就再广播一次
            Set<Message> prePrepareSet = msgCache.get(Message.PREPREPARE);
            if (prePrepareSet != null) {
                for (Message m : prePrepareSet) {
                    PrePrepareMsg ppMsg = (PrePrepareMsg) m;
                    if (ppMsg.v == v && ppMsg.i == id && ppMsg.m.equals(msg)) {
                        m.receiveTime = msg.receiveTime;
                        m.sendTime = timestamp;
                        Simulator.sendMsgToOthers(m, id, sendTag);
                        return;
                    }
                }
            }
            //否则如果不会超过水位就生成新的prePrepare消息并广播,同时启动timeout
            if (inWater(n + 1)) {
                n++;
                Message prePrepareMsg = new PrePrepareMsg( id + "||" + n,v, n, requestMsg, id, id, id, requestMsg.receiveTime);
                addMessageToCache(prePrepareMsg);
                prePrepareMsg.sendTime = timestamp;
                Simulator.sendMsgToOthers(prePrepareMsg, id, sendTag);
            }
        }
    }

    public void receivePreprepare(Message msg) {
        if (isTimeOut) return;
        PrePrepareMsg prePrepareMsg = (PrePrepareMsg) msg;
        int msgv = prePrepareMsg.v;
        int msgn = prePrepareMsg.n;
        int i = prePrepareMsg.i;
        //检查消息的视图是否与节点视图相符，消息的发送者是否是主节点，
        //消息的视图是否合法，序号是否在水位内
        if (msgv < v || !inWater(msgn) || !Simulator.getPrimaryNodeIds().contains(i) || !hasNewView(v)) {
            return;
        }
        if(msgCache != null && msgCache.get(prePrepareMsg.type) != null && msgCache.get(prePrepareMsg.type).contains(prePrepareMsg))
            return;
        //把prePrepare消息和其包含的request消息放进缓存
        receiveRequest(prePrepareMsg.m);
        addMessageToCache(msg);
        n = Math.max(n, prePrepareMsg.n);
        //生成Prepare消息并广播
        String d = Utils.getMD5Digest(prePrepareMsg.mString());
        Message prepareMsg = new PrepareMsg(prePrepareMsg.proposalHash, msgv, msgn, d, id, id, id, msg.receiveTime);
        if (isInMsgCache(prepareMsg)) return;
        addMessageToCache(prepareMsg);
        prepareMsg.sendTime = timestamp;
        Simulator.sendMsgToOthers(prepareMsg, id, sendTag);
    }

    public void receivePrepare(Message msg) {
        if (isTimeOut) return;
        PrepareMsg prepareMsg = (PrepareMsg) msg;
        int msgv = prepareMsg.v;
        int msgn = prepareMsg.n;
        //检查缓存中是否有这条消息，消息的视图是否合法，序号是否在水位内
        if (isInMsgCache(msg) || msgv < v || !inWater(msgn) || !hasNewView(v)) {
            return;
        }
        //把prepare消息放进缓存
        addMessageToCache(msg);
    }

    public void receiveCommit(Message msg) {
        if (isTimeOut) return;
        CommitMsg commitMsg = (CommitMsg) msg;
        int msgv = commitMsg.v;
        int msgn = commitMsg.n;
        //检查消息的视图是否合法，序号是否在水位内
        if (isInMsgCache(msg) || msgv < v || !inWater(msgn) || !hasNewView(v)) {
            return;
        }
        //把commit消息放进缓存
        addMessageToCache(msg);
    }

    public void receiveTimeOut(Message msg) {
        TimeOutMsg tMsg = (TimeOutMsg) msg;
        //如果消息已经进入稳态，就忽略这条消息
        if (tMsg.n <= lastRepNum || tMsg.v < v) return;
        //如果不再会有新的request请求，则停止timeOut
        if (reqStats.size() >= Simulator.REQNUM) return;
        isTimeOut = true;
        //发送viewChange消息
        Map<Integer, LastReply> ss = checkPoints.get(h);
        Set<Message> C = computeC();
        Map<Integer, Set<Message>> P = computeP();
        Message vm = new ViewChangeMsg(v + 1, h, ss, C, P, id, id, id, msg.receiveTime);
        addMessageToCache(vm);
        vm.sendTime = timestamp;
        Simulator.sendMsgToOthers(vm, id, sendTag);
    }

    public void receiveCheckPoint(Message msg) {
        CheckPointMsg checkptMsg = (CheckPointMsg) msg;
        int msgv = checkptMsg.v;
        //检查缓存中是否有这条消息，消息的视图是否合法
        if (msgv < v) {
            return;
        }
        //把checkpoint消息放进缓存
        addMessageToCache(msg);
    }


    public void receiveViewChange(Message msg) {
        ViewChangeMsg vcMsg = (ViewChangeMsg) msg;
        int msgv = vcMsg.v;
        int msgn = vcMsg.sn;
        //检查缓存中是否有这条消息，消息的视图是否合法
        if (msgv <= v || msgn < h) {
            return;
        }
        //把checkpoint消息放进缓存
        addMessageToCache(msg);
        //是否收到了2f+1条viewChange消息
        if (viewChanged(vcMsg)) {
            v = vcMsg.v;
            h = vcMsg.sn;
            lastRepNum = h;
            lastReplyMap = vcMsg.ss;
            n = lastRepNum;
            Map<Integer, Set<Message>> prePrepareMap = vcMsg.P;
            if (prePrepareMap != null) {
                for (Integer nn : prePrepareMap.keySet()) {
                    n = Math.max(n, nn);
                }
            }
            isTimeOut = false;
            setTimer(lastRepNum + 1, msg.receiveTime);
            if (Simulator.IsPrimaryNodeId(id)) {
                //发送NewView消息
                Map<String, Set<Message>> VONMap = computeVON();
                Message nvMsg = new NewViewMsg(v, VONMap.get("V"), VONMap.get("O"), VONMap.get("N"), id, id, id, msg.receiveTime);
                addMessageToCache(nvMsg);
                nvMsg.sendTime = timestamp;
                Simulator.sendMsgToOthers(nvMsg, id, sendTag);
                //发送所有不在O内的request消息的prePrepare消息
                Set<Message> reqSet = msgCache.get(Message.REQUEST);
                if (reqSet == null) reqSet = new HashSet<>();
                Set<Message> OSet = VONMap.get("O");
                reqSet.removeAll(OSet);
                for (Message m : reqSet) {
                    RequestMsg reqMsg = (RequestMsg) m;
                    reqMsg.receiveTime = msg.receiveTime;
                    receiveRequest(reqMsg);
                }
            }
        }
    }

    public void receiveNewView(Message msg) {
        NewViewMsg nvMsg = (NewViewMsg) msg;
        int msgv = nvMsg.v;
        //检查缓存中是否有这条消息，消息的视图是否合法
        if (msgv < v) {
            return;
        }
        v = msgv;
        addMessageToCache(msg);

        //逐一处理new view中的prePrepare消息
        Set<Message> O = nvMsg.O;
        for (Message m : O) {
            PrePrepareMsg ppMsg = (PrePrepareMsg) m;
            PrePrepareMsg newPPm = new PrePrepareMsg(ppMsg.proposalHash,v, ppMsg.n, ppMsg.m, ppMsg.i, msg.sndId, msg.rcvId, msg.receiveTime);
            receivePreprepare(newPPm);
        }
        Set<Message> N = nvMsg.N;
        for (Message m : N) {
            PrePrepareMsg ppMsg = (PrePrepareMsg) m;
            PrePrepareMsg newPPm = new PrePrepareMsg(ppMsg.proposalHash,ppMsg.v, ppMsg.n, ppMsg.m, ppMsg.i, msg.sndId, msg.rcvId, msg.receiveTime);
            receivePreprepare(newPPm);
        }
    }

    /**
     * 将消息存到缓存中
     *
     * @param m
     */
    private boolean isInMsgCache(Message m) {
        Set<Message> msgSet = msgCache.get(m.type);
        if (msgSet == null) {
            return false;
        }
        return msgSet.contains(m);
    }

    /**
     * 将消息存到缓存中
     *
     * @param m
     */
    public void addMessageToCache(Message m) {
        Set<Message> msgSet = msgCache.get(m.type);
        if (msgSet == null) {
            msgSet = new HashSet<>();
            msgCache.put(m.type, msgSet);
        }
        msgSet.add(m);
    }

    /**
     * 删除序号n之前的所有缓存消息
     *
     * @param n
     */
    private void deleteCache(int n) {
        Map<Integer, LastReply> lastReplyMap = checkPoints.get(n);
        if (lastReplyMap == null) return;
        for (Integer type : msgCache.keySet()) {
            Set<Message> msgSet = msgCache.get(type);
            if (msgSet != null) {
                Iterator<Message> it = msgSet.iterator();
                while (it.hasNext()) {
                    Message m = it.next();
                    if (m instanceof RequestMsg) {
                        RequestMsg mm = (RequestMsg) m;
                        if (lastReplyMap.get(mm.c) != null && mm.t <= lastReplyMap.get(mm.c).t) {
                            it.remove();
                        }
                    } else if (m instanceof PrePrepareMsg) {
                        PrePrepareMsg mm = (PrePrepareMsg) m;
                        if (mm.n <= n) {
                            it.remove();
                        }
                    } else if (m instanceof PrepareMsg) {
                        PrepareMsg mm = (PrepareMsg) m;
                        if (mm.n <= n) {
                            it.remove();
                        }
                    } else if (m instanceof CommitMsg) {
                        CommitMsg mm = (CommitMsg) m;
                        if (mm.n <= n) {
                            it.remove();
                        }
                    } else if (m instanceof CheckPointMsg) {
                        CheckPointMsg mm = (CheckPointMsg) m;
                        if (mm.n < n) {
                            it.remove();
                        }
                    } else if (m instanceof ViewChangeMsg) {
                        ViewChangeMsg mm = (ViewChangeMsg) m;
                        if (mm.sn < n) {
                            it.remove();
                        }
                    }
                }
            }
        }
    }

    private void deleteCheckPts(int n) {
        Iterator<Map.Entry<Integer, Map<Integer, LastReply>>> it = checkPoints.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Map<Integer, LastReply>> entry = it.next();
            int sn = entry.getKey();
            if (sn < n) {
                it.remove();
            }
        }
    }

    /**
     * 判断一个视图编号是否有NewView的消息基础
     *
     * @return
     */
    public boolean hasNewView(int v) {
        if (v == 0)
            return true;
        Set<Message> msgSet = msgCache.get(Message.NEWVIEW);
        if (msgSet != null) {
            for (Message m : msgSet) {
                NewViewMsg nMsg = (NewViewMsg) m;
                if (nMsg.v == v) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean inWater(int n) {
        return n == 0 || (n > h && n < h + L);
    }

    private Set<Message> computeC() {
        if (h == 0) return null;
        Set<Message> result = new HashSet<>();
        Set<Message> checkptSet = msgCache.get(Message.CHECKPOINT);
        for (Message msg : checkptSet) {
            CheckPointMsg ckpt = (CheckPointMsg) msg;
            if (ckpt.n == h) {
                result.add(msg);
            }
        }
        return result;
    }

    private Map<Integer, Set<Message>> computeP() {
        Map<Integer, Set<Message>> result = new HashMap<>();
        Set<Message> prePrepareSet = msgCache.get(Message.PREPREPARE);
        if (prePrepareSet == null) return null;
        for (Message msg : prePrepareSet) {
            PrePrepareMsg ppm = (PrePrepareMsg) msg;
            if (ppm.n > h && prepared(ppm)) {
                Set<Message> set = result.get(ppm.n);
                if (set == null) {
                    set = new HashSet<>();
                    result.put(ppm.n, set);
                }
                set.add(msg);
            }
        }
        return result;
    }

    private Map<String, Set<Message>> computeVON() {
        int maxN = h;
        Set<Message> V = new HashSet<>();
        Set<Message> O = new HashSet<>();
        Set<Message> N = new HashSet<>();
        Set<Message> vcSet = msgCache.get(Message.VIEWCHANGE);
        for (Message msg : vcSet) {
            ViewChangeMsg ckpt = (ViewChangeMsg) msg;
            if (ckpt.v == v) {
                V.add(msg);
                Map<Integer, Set<Message>> ppMap = ckpt.P;
                if (ppMap == null) continue;
                for (Integer n : ppMap.keySet()) {
                    Set<Message> ppSet = ppMap.get(n);
                    if (ppSet == null) continue;
                    for (Message m : ppSet) {
                        PrePrepareMsg ppm = (PrePrepareMsg) m;
                        Message ppMsg = new PrePrepareMsg(ppm.proposalHash,v, n, ppm.m, id, id, id, 0);
                        O.add(ppMsg);
                        maxN = Math.max(maxN, n);
                    }
                }
            }
        }
        for (int i = h; i < maxN; i++) {
            boolean flag = false;
            for (Message msg : O) {
                PrePrepareMsg ppm = (PrePrepareMsg) msg;
                if (ppm.n == i) {
                    flag = true;
                    break;
                }
            }
            if (!flag) {
                Message ppMsg = new PrePrepareMsg(id + "||" + n,v, n, null, id, id, id, 0);
                N.add(ppMsg);
            }
        }
        Map<String, Set<Message>> map = new HashMap<>();
        map.put("V", V);
        map.put("O", O);
        map.put("N", N);
        n = maxN;
        return map;
    }

    public void setTimer(int n, long time) {
        Message timeOutMsg = new TimeOutMsg(v, n, id, id, time + Simulator.TIMEOUT);
        timeOutMsg.sendTime = timestamp;
        Simulator.sendMsg(timeOutMsg, sendTag);
    }

}
