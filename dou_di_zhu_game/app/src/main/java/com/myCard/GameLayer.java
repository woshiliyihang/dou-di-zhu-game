package com.myCard;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.MotionEvent;

import org.cocos2d.R;
import org.cocos2d.actions.instant.CCCallFunc;
import org.cocos2d.actions.interval.CCDelayTime;
import org.cocos2d.actions.interval.CCMoveTo;
import org.cocos2d.actions.interval.CCSequence;
import org.cocos2d.layers.CCLayer;
import org.cocos2d.menus.CCMenu;
import org.cocos2d.menus.CCMenuItem;
import org.cocos2d.menus.CCMenuItemFont;
import org.cocos2d.nodes.CCDirector;
import org.cocos2d.nodes.CCSprite;
import org.cocos2d.types.CGPoint;
import org.cocos2d.types.CGSize;

import java.util.List;
import java.util.Random;
import java.util.Vector;

/**
 * Created by jason li on 15/12/4.
 */
public class GameLayer extends CCLayer {

    Context context;
    Handler handler;
    CGSize s;
    Card[] cardArr = new Card[54];//所有纸牌合集
    CCSprite dizhuImg;//地主图标
    List<Card> dizhuList = new Vector<Card>();//地主牌
    private Bitmap cardBgBitmap;//纸牌背景
    public List<Card> playerList[] = new Vector[3];//三方的纸牌合集
    float cardScale = 0.7f;//卡牌缩放比例
    float movetime = 0.1f;//发牌时移动的总时间
    float comTimeInval = 0;//公共计时针
    int turn = -1;//轮流标志位
    int dizhuFlag = -1;//谁是地主
    int[] flag = new int[3];// 判断当前是否要牌
    List<Card> outList[] = new Vector[3];// 已出牌表
    CCMenu ccMenu;//公共菜单
    int menuzindex = 9;//菜单层级
    Boolean start=true;//开始游戏标志位

    //保留变量
    // 屏幕宽度和高度
    int screen_height;
    int screen_width;

    //切换时间
    int nextTimeinval = 2;//电脑自动切换时间
    int msgcloseinval = 1;//不要提示消除时间
    int chupaiclosetimeinval = 3;//出去的牌消失时间

    Object comParms, oneoutlist, twooutlist, threeoutlist;//公共参数


    public GameLayer(Context context, Handler handler) {
        super();

        this.context = context;
        this.handler = handler;


        //清空对象

        Common.cards = playerList;//添加对象
        Common.dizhuFlag=-1;
        Common.currentFlag=-1;
        Common.oppoerFlag=-1;


        //逻辑开始
        s = CCDirector.sharedDirector().winSize();

        screen_height = (int) s.getHeight();
        screen_width = (int) s.getWidth();

        //开启触屏事件
        setIsTouchEnabled(true);

        //初始化数据
        InitView();

    }

    private void InitView() {

        //公共菜单
        ccMenu = CCMenu.menu();
        addChild(ccMenu, menuzindex);
        ccMenu.setPosition(CGPoint.ccp(0, 0));

        //初始化已出牌合集
        for (int i = 0; i < 3; i++) {
            outList[i] = new Vector<Card>();
        }

        // 遍历牌
        int count = 0;//卡牌 index
        ApplicationInfo appInfo = context.getApplicationInfo();// 获取应用名称
        for (int i = 1; i <= 4; i++) {// 四种花色
            for (int j = 3; j <= 15; j++) {// 15张牌
                // 根据名字找出ID
                String name = "a" + i + "_" + j;
                int id = context.getResources().getIdentifier(name, "drawable", appInfo.packageName);// 获取资源id
                Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), id);
                //缩小比例
                bitmap = Card.ScaleMyBitmap(bitmap, cardScale);
                cardArr[count] = new Card(bitmap.getWidth(), bitmap.getHeight(), bitmap, name);
                cardArr[count].huanYuan();
                cardArr[count].clicked=false;
                count++;
            }
        }
        //放入大小王
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(),
                R.drawable.a5_16);
        bitmap = Card.ScaleMyBitmap(bitmap, cardScale);
        cardArr[52] = new Card(bitmap.getWidth(), bitmap.getHeight(), bitmap, "a5_16");
        cardArr[52].huanYuan();
        cardArr[52].clicked=false;
        Bitmap bitmap2 = BitmapFactory.decodeResource(context.getResources(),
                R.drawable.a5_17);
        bitmap2 = Card.ScaleMyBitmap(bitmap2, cardScale);
        cardArr[53] = new Card(bitmap2.getWidth(), bitmap2.getHeight(), bitmap2, "a5_17");
        cardArr[53].huanYuan();
        cardArr[53].clicked=false;

        // 地主图标
        Bitmap bitmap1 = BitmapFactory.decodeResource(context.getResources(), R.drawable.image0);
        //bitmap1=Card.ScaleMyBitmap(bitmap1,0.7f);
        dizhuImg = CCSprite.sprite(bitmap1, "dizhuImg");
        dizhuImg.setScale(0.74f);
        // 设置背景
        Bitmap bitmap3 = BitmapFactory.decodeResource(context.getResources(), R.drawable.bg);
        CCSprite bg = CCSprite.sprite(bitmap3, "bg");
        addChild(bg);
        bg.setScale(1.7f);
        bg.setPosition(CGPoint.ccp(s.getWidth() / 2, s.getHeight() / 2));


        //纸牌背面
        cardBgBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cardbg1);
        cardBgBitmap = Card.ScaleMyBitmap(cardBgBitmap, cardScale);

        //洗牌
        washCards();

        //发牌
        pushCard();

        //55开始流程整理顺序开始抢地主
        CCSequence ccSequence = CCSequence.actions(CCDelayTime.action(55 * movetime), CCCallFunc.action(this, "startGame"));
        runAction(ccSequence);//开始执行

    }

    //获取bitmap对象
    public Bitmap getBitmapObj(int rid){
        return BitmapFactory.decodeResource(context.getResources(),rid);
    }

    //开始流程
    public void startGame() {

        // 重新排序
        for (int i = 0; i < 3; i++) {
            Common.setOrder(playerList[i]);
            //重新显示顺序
            rePos(playerList[i], i);
        }

        //按钮出现
        CCMenuItem item = CCMenuItemFont.item("抢地主", this, "QiangDiZhuFun");
        CCMenuItem item2 = CCMenuItemFont.item("不抢", this, "QiangDiZhuFun2");
        ccMenu.addChild(item);
        ccMenu.addChild(item2);

        //设置按钮位置
        item2.setPosition(CGPoint.ccp(s.getWidth() / 2 - 170, s.getHeight() / 2));
        item.setPosition(CGPoint.ccp(s.getWidth() / 2 + 170, s.getHeight() / 2));
        item2.setScale(2.4f);
        item.setScale(2.4f);


    }

    //抢地主
    public void QiangDiZhuFun(Object sender) {

        //显示地主牌
        for (Card item : dizhuList) {
            item.huanYuan();
        }

        //添加数组
        playerList[1].addAll(dizhuList);
        dizhuList.clear();
        dizhuFlag = 1;
        Common.dizhuFlag = 1;
        turn = 1;


        //移除菜单
        ccMenu.removeAllChildren(true);
        //添加新菜单
        CCMenuItem ccMenuItem = CCMenuItemFont.item("4");//倒计时内容
        ccMenu.addChild(ccMenuItem);

        ccMenuItem.setPosition(CGPoint.ccp(s.getWidth() / 2, s.getHeight() * 0.4f));
        ccMenuItem.setScale(4.7f);

        //定时三秒中开始
        comTimeInval = 0;
        schedule("setTimeOutVal", 1f);//一秒钟进行一次
    }

    //获取最好的地主
    public int getBestDizhuFlag() {
        int count1 = 0, count2 = 0;
        for (int i = 0, len = playerList[0].size(); i < len; i++) {
            if (Common.getValue(playerList[0].get(i)) > 14)
                count1++;
        }
        for (int i = 0, len = playerList[2].size(); i < len; i++) {
            if (Common.getValue(playerList[2].get(i)) > 14)
                count2++;
        }
        if (count1 > count2)
            return 0;
        else {
            return 2;
        }
    }

    //不抢地主
    public void QiangDiZhuFun2(Object sender) {

        dizhuFlag = Common.getBestDizhuFlag();
        Common.dizhuFlag = dizhuFlag;
        //翻开棋牌
        for (Card card : dizhuList) {
            card.huanYuan();
        }

        //设置参数
        playerList[dizhuFlag].addAll(dizhuList);
        dizhuList.clear();
        turn = dizhuFlag;


        //移除菜单
        ccMenu.removeAllChildren(true);
        //添加新菜单
        CCMenuItem ccMenuItem = CCMenuItemFont.item("4");//倒计时内容
        ccMenu.addChild(ccMenuItem);

        ccMenuItem.setPosition(CGPoint.ccp(s.getWidth() / 2, s.getHeight() * 0.4f));
        ccMenuItem.setScale(4.7f);

        //定时三秒中开始
        comTimeInval = 0;
        schedule("setTimeOutVal", 1f);//一秒钟进行一次
    }

    //快速添菜单
    public CCMenu addWorldMenu(CCMenuItem... items) {
        CCMenu ccMenu = CCMenu.menu(items);
        ccMenu.setPosition(CGPoint.ccp(0, 0));
        addChild(ccMenu);
        return ccMenu;
    }


    //定时执行
    public void setTimeOutVal(float t) {

        if (comTimeInval > 2) {
            comTimeInval = 0;
            comParms = null;
            ccMenu.removeAllChildren(true);
            //排序
            Common.setOrder(playerList[dizhuFlag]);
            rePos(playerList[dizhuFlag], dizhuFlag);
            //如果不是主角就还原背面
            if (dizhuFlag != 1) {
                for (Card item : playerList[dizhuFlag]) {
                    item.setBeiMian(cardBgBitmap);
                }
            }
            //给地主加表示
            addDiZhuFlag();
            //判断使用什么方向逻辑
            comTimeInval = 0;
            unschedule("setTimeOutVal");//停止循环
            schedule("changeAction", 1f);//一秒钟执行一次
            return;
        }

        //显示倒计时文字
        ccMenu.removeAllChildren(true);
        CCMenuItem ccMenuItem = CCMenuItemFont.item("" + (int) (3 - comTimeInval));
        ccMenu.addChild(ccMenuItem);

        ccMenuItem.setPosition(CGPoint.ccp(s.getWidth() / 2, s.getHeight() * 0.4f));
        ccMenuItem.setScale(4.7f);

        comTimeInval++;
    }

    //判断使用方向逻辑
    public void changeAction(float t) {

        if (!start){
            unschedule("changeAction");
            return;
        }

        if (turn == 0) {
            playerOne();
        } else if (turn == 1) {
            playerTwo();
        } else if (turn == 2) {
            playerThree();
        }
        win();
    }

    // 判断成功
    public void win() {

        String outmsg="";

        //判断牌出完了没有
        if (playerList[0].size()<=0){//左边赢了
            if (dizhuFlag==0){
                outmsg="地主胜利";
            }else if (dizhuFlag==1){
                outmsg="你输了，农民胜利";
            }else if (dizhuFlag==2){
                outmsg="您的队友赢了，农民胜利";
            }
        }else if (playerList[1].size()<=0){//我赢了
            if (dizhuFlag==0){
                outmsg="我赢了，农民胜利";
            }else if (dizhuFlag==1){
                outmsg="地主胜利";
            }else if (dizhuFlag==2){
                outmsg="我赢了，农民胜利";
            }
        }else if (playerList[2].size()<=0){//右边赢了
            if (dizhuFlag==0){
                outmsg="您的队友赢了，农民胜利";
            }else if (dizhuFlag==1){
                outmsg="你输了,农民胜利";
            }else if (dizhuFlag==2){
                outmsg="地主胜利";
            }
        }

        if (outmsg.equals("")){//不发信息
            return;
        }

        start=false;
        for (int i=0; i<cardArr.length; i++){
            cardArr[i].huanYuan();
        }

        //消息
        Message msg=new Message();
        msg.what=3;
        Bundle bundle=new Bundle();
        bundle.putString("data",outmsg);
        msg.setData(bundle);
        handler.sendMessage(msg);

    }

    @Override
    public boolean ccTouchesBegan(MotionEvent event) {
        //判断是否点中牌了
        playClick(event);
        return super.ccTouchesBegan(event);
    }

    //猪脚牌的点击事件
    public void playClick(MotionEvent e){
        CGPoint convertedLocation = CCDirector.sharedDirector()
                .convertToGL(CGPoint.make(e.getX(), e.getY()));
        float x=convertedLocation.x;
        float y=convertedLocation.y;
        for (Card card : playerList[1]) {
            float width = card.width;
            float heigth = card.height;
            float mx = card.getPosition().x;
            float my = card.getPosition().y;
            float minx = mx - width / 2;
            float miny = my - heigth / 2;
            float maxx = minx+width*0.74f;
            float maxy = miny+heigth;
            if ((x>minx && x<maxx) && (y>miny && y<maxy)){
                //点中
                if (card.clicked){
                    card.MReclick();
                }else{
                    card.Mclick();
                }
            }
        }
    }

    //清除已经出去的牌
    public void OneClearOut(float t){
        if (oneoutlist!=null){
            List<Card> mlist=(List<Card>)oneoutlist;
            for (Card item : mlist){
                removeChild(item,true);
            }
            oneoutlist=null;
        }
        unschedule("OneClearOut");
    }

    //清除已经出去的牌
    public void TwoClearOut(float t){
        if (twooutlist!=null){
            List<Card> mlist=(List<Card>)twooutlist;
            for (Card item : mlist){
                removeChild(item,true);
            }
            twooutlist=null;
        }
        unschedule("TwoClearOut");
    }

    //清除已经出来的牌
    public void ThreeClearOut(float t){

        if (threeoutlist!=null){
            List<Card> mlist=(List<Card>)threeoutlist;
            for (Card item : mlist){
                removeChild(item,true);
            }
            threeoutlist=null;
        }
        unschedule("ThreeClearOut");
    }

    //左边
    public void playerOne() {
        comTimeInval++;
        //3秒钟之后执行内容

        //出牌 出牌
        if (comTimeInval==1){
            //左边
            //出牌逻辑
            List<Card> player0 = null;
            Common.currentFlag = 0;
            if (flag[1] == 0 && flag[2] == 0) {
                player0 = Common.getBestAI(playerList[0], null);
            } else if (flag[2] == 0) {
                Common.oppoerFlag = 1;
                player0 = Common.getBestAI(playerList[0], outList[1]);
            } else {
                Common.oppoerFlag = 2;
                player0 = Common.getBestAI(playerList[0], outList[2]);
            }
            outList[0].clear();
            if (player0 != null) {
                outList[0].addAll(player0);
                playerList[0].removeAll(player0);
                Common.setOrder(player0);
                //修改出牌的位置
                for (int i=0; i<player0.size(); i++){
                    float x=347+i*cardArr[0].width;
                    float y=s.getHeight()*0.7f;
                    CCMoveTo ccMoveTo=CCMoveTo.action(0.1f, CGPoint.ccp(x, y));
                    player0.get(i).runAction(ccMoveTo);
                    player0.get(i).huanYuan();
                }
                //定时消除已出牌
                oneoutlist = player0;//需要消除参数
                schedule("OneClearOut",chupaiclosetimeinval);
                //排序整理
                Common.setOrder(playerList[0]);
                rePos(playerList[0], 0);
                flag[0] = 1;
            } else {
                flag[0] = 0;
                //显示不要信息
                CCMenuItemFont ccMenuItemFont=CCMenuItemFont.item("不要");
                ccMenuItemFont.setScale(4f);
                CCMenu ccMenu1=CCMenu.menu(ccMenuItemFont);
                ccMenuItemFont.setPosition(CGPoint.ccp(cardArr[0].width*3,s.getHeight()-200));
                addChild(ccMenu1);
                ccMenu1.setPosition(CGPoint.ccp(0, 0));
                comParms=ccMenu1;
                //定时销毁
                schedule("clearMenu",msgcloseinval);
            }
        }


        //时间到！
        if (comTimeInval > nextTimeinval) {
            nextTurn();//下一个人物
            ccMenu.removeAllChildren(true);
            comTimeInval = 0;//计时器归零
        }

//        else {
//            //刷新文字
//            ccMenu.removeAllChildren(true);
//            CCMenuItem ccMenuItem = CCMenuItemFont.item("" + (int) (4 - comTimeInval));
//            ccMenuItem.setScale(3.4f);
//            ccMenu.addChild(ccMenuItem);
//            //位置
//            float x = 300;
//            float y = s.getHeight() - 300;
//            ccMenuItem.setPosition(CGPoint.ccp(x, y));
//        }


    }

    public void playerTwo() {
        //我
        comTimeInval++;
        //3秒钟之后执行内容
        if (comTimeInval > 27) {
            outList[1].clear();
            flag[1] = 0;
            ccMenu.removeAllChildren(true);
            nextTurn();
            comTimeInval = 0;
        } else {
            //刷新文字
            ccMenu.removeAllChildren(true);
            CCMenuItem ccMenuItem = CCMenuItemFont.item("" + (int) (27 - comTimeInval));
            ccMenuItem.setScale(3.4f);
            //出牌
            CCMenuItemFont ccMenuItemFont=CCMenuItemFont.item("出牌",this,"ChuPai");
            CCMenuItemFont ccMenuItemFont1=CCMenuItemFont.item("不要",this,"BuYao");
            ccMenuItemFont.setScale(3f);
            ccMenuItemFont1.setScale(3f);
            ccMenu.addChild(ccMenuItemFont);
            ccMenu.addChild(ccMenuItemFont1);
            //不要
            ccMenuItemFont.setPosition(CGPoint.ccp(s.getWidth() / 2 - 240, 370));
            ccMenuItemFont1.setPosition(CGPoint.ccp(s.getWidth() / 2 + 240, 370));
            ccMenu.addChild(ccMenuItem);
            //位置
            float x = s.getWidth() / 2 - 20;
            float y = 370;
            ccMenuItem.setPosition(CGPoint.ccp(x, y));
        }
    }

    public void playerThree() {
        //右边啊
        comTimeInval++;
        //3秒钟之后执行内容


        if (comTimeInval==2){
            Common.currentFlag = 2;
            List<Card> player2 = null;
            if (flag[1] == 0 && flag[0] == 0) {
                player2 = Common.getBestAI(playerList[2], null);
            } else if (flag[1] == 0) {
                player2 = Common.getBestAI(playerList[2], outList[0]);
                Common.oppoerFlag = 0;
            } else {
                player2 = Common.getBestAI(playerList[2], outList[1]);
                Common.oppoerFlag = 1;
            }
            outList[2].clear();
            if (player2 != null) {
                outList[2].addAll(player2);
                playerList[2].removeAll(player2);
                //修改出牌的位置
                for (int i=0; i<player2.size(); i++){
                    float x=s.getWidth()-347-i*cardArr[0].width;
                    float y=s.getHeight()*0.7f;
                    CCMoveTo ccMoveTo=CCMoveTo.action(0.1f, CGPoint.ccp(x, y));
                    player2.get(i).runAction(ccMoveTo);
                    player2.get(i).huanYuan();
                }
                //定时消除已出牌
                threeoutlist = player2;//需要消除参数
                schedule("ThreeClearOut", chupaiclosetimeinval);
                Common.setOrder(playerList[0]);
                rePos(playerList[2], 2);
                flag[2] = 1;
            } else {
                flag[2] = 0;
                //显示不要信息
                CCMenuItemFont ccMenuItemFont=CCMenuItemFont.item("不要");
                ccMenuItemFont.setScale(4f);
                CCMenu ccMenu1=CCMenu.menu(ccMenuItemFont);
                ccMenuItemFont.setPosition(CGPoint.ccp(s.getWidth()-cardArr[0].width*3,s.getHeight()-200));
                addChild(ccMenu1);
                ccMenu1.setPosition(CGPoint.ccp(0, 0));
                comParms=ccMenu1;
                //定时销毁
                schedule("clearMenu",msgcloseinval);
            }
        }

        if (comTimeInval > nextTimeinval) {
            nextTurn();//下一个人物
            ccMenu.removeAllChildren(true);
            comTimeInval = 0;//计时器归零
        }

//        else {
//            //刷新文字
//            ccMenu.removeAllChildren(true);
//            CCMenuItem ccMenuItem = CCMenuItemFont.item("" + (int) (4 - comTimeInval));
//            ccMenuItem.setScale(3.4f);
//            ccMenu.addChild(ccMenuItem);
//            //位置
//            float x = s.getWidth() - 300;
//            float y = s.getHeight() - 300;
//            ccMenuItem.setPosition(CGPoint.ccp(x, y));
//        }

    }

    public void ChuPai(Object obj){
        // 选出最好的出牌(跟牌和主动出牌)
        List<Card> oppo = null;//上家的牌
        if (outList[0].size() <= 0 && outList[2].size() <= 0) {
            oppo = null;
        } else {
            oppo = (outList[0].size() > 0) ? outList[0] : outList[2];
        }
        List<Card> mybest = Common.getMyBestCards(playerList[1], oppo);//获取我点选能够出的纸牌
        // 如果没有好的牌就不出
        if (mybest == null)
            return;

        // 加入outlist
        outList[1].clear();
        outList[1].addAll(mybest);
        // 退出playerlist
        playerList[1].removeAll(mybest);
        //修改出牌的位置
        for (int i=0; i<mybest.size(); i++){
            float x=s.getWidth()/2-(mybest.size()*cardArr[0].width/2)+i*cardArr[0].width;
            float y=s.getHeight()*0.44f;
            CCMoveTo ccMoveTo=CCMoveTo.action(0.1f, CGPoint.ccp(x, y));
            mybest.get(i).runAction(ccMoveTo);
            mybest.get(i).huanYuan();
        }
        //定时消除已出牌
        twooutlist = mybest;//需要消除参数
        schedule("TwoClearOut", chupaiclosetimeinval);
        //修改显示位置
        Common.setOrder(playerList[1]);
        rePos(playerList[1], 1);
        flag[1] = 1;
        nextTurn();
        ccMenu.removeAllChildren(true);
        comTimeInval = 0;
    }

    //不要
    public void BuYao(Object obj){
        if (outList[0].size() == 0 && outList[2].size() == 0) {
            Message msg=new Message();
            msg.what=4;
            msg.obj="前面没人出牌，你必须出牌!";
            handler.sendMessage(msg);
            return;
        }
        nextTurn();
        flag[1] = 0;
        comTimeInval = 0;
        ccMenu.removeAllChildren(true);
        //显示不要信息
        CCMenuItemFont ccMenuItemFont=CCMenuItemFont.item("不要");
        ccMenuItemFont.setScale(4f);
        CCMenu ccMenu1=CCMenu.menu(ccMenuItemFont);
        float fontx = s.getWidth()/2-50;
        float fonty = s.getHeight()*0.37f;
        ccMenuItemFont.setPosition(CGPoint.ccp(fontx, fonty));
        addChild(ccMenu1);
        ccMenu1.setPosition(CGPoint.ccp(0, 0));
        comParms=ccMenu1;
        //定时销毁
        schedule("clearMenu",1f);
    }





    //定时销毁menu里面指定的元素
    public void clearMenu(float t){
        if (comParms!=null){
            //ccMenu.removeChild((CCMenuItemFont)comParms,true);
            removeChild((CCMenu)comParms,true);
        }
        unschedule("clearMenu");
    }

    // 下一个玩家
    public void nextTurn() {
        turn = (turn + 1) % 3;
    }

    //给地主加表示
    public void addDiZhuFlag() {
        float paddingx = 40;//相对于屏幕边界
        float postionMove = cardArr[0].width * 0.74f;//两张纸牌位置偏移量
        if (dizhuFlag == 0) {
            float x = paddingx + cardArr[0].width*2;
            float y = s.getHeight() - cardArr[0].height * 2 + 140;
            addChild(dizhuImg);
            dizhuImg.setPosition(CGPoint.ccp(x, y));
        } else if (dizhuFlag == 1) {
            float x = s.getWidth() / 2 - (17 * postionMove / 2) + postionMove / 2 + 274;
            float y = cardArr[0].height + 140;
            addChild(dizhuImg);
            dizhuImg.setPosition(CGPoint.ccp(x, y));
        } else if (dizhuFlag == 2) {
            float x = s.getWidth() - paddingx - cardArr[0].width*2;
            float y = s.getHeight() - cardArr[0].height * 2 + 140;
            addChild(dizhuImg);
            dizhuImg.setPosition(CGPoint.ccp(x, y));
        }
    }


    //整理牌顺序再显示
    public void rePos(List<Card> mlist, int i) {

        float width = cardArr[0].width;
        float height = cardArr[0].height;

        //移除所有元素
        for (int j = 0; j < mlist.size(); j++) {
            removeChild(mlist.get(j), false);
        }

        //重新添加
        int onem = 0;
        float postionMove = width * 0.74f;//两张纸牌位置偏移量
        for (int a = 0; a < mlist.size(); a++) {
            addChild(mlist.get(a));
            float x = 0;
            float y = 0;
            if (i == 0) {
                x =  width;
                y = s.getHeight() - onem * postionMove * 0.34f - height * 0.6f;
            } else if (i == 1) {
                x = s.getWidth() / 2 - (mlist.size() * postionMove / 2) + onem * postionMove + postionMove / 2;
                y = height;
            } else if (i == 2) {
                x = s.getWidth()  - width;
                y = s.getHeight() - onem * postionMove * 0.34f - height * 0.6f;
            }
            mlist.get(a).setPosition(CGPoint.ccp(x, y));
            onem++;
        }
    }


    //发牌
    private void pushCard() {
        //创建三牌的集合对象
        for (int i = 0; i < 3; i++) {
            playerList[i] = new Vector<Card>();
        }
        //中心位置坐标
        float centerx = s.getWidth() / 2;
        float centery = s.getHeight() - cardArr[0].height * 1.4f;
        float postionMove = cardArr[0].width * 0.74f;//两张纸牌位置偏移量
        int onem = 0, twom = 0, threem = 0, fourm = 0;//记录每张牌的相隔距离
        float zhupaiPos = cardArr[0].width * 2f;//地主牌间隔
        for (int i = 0; i < cardArr.length; i++) {
            float deltime = i * movetime;//等待时间
            if (i > 50) {
                //地主牌移动到指定位置
                addChild(cardArr[i]);//加入时间
                dizhuList.add(cardArr[i]);
                cardArr[i].setPosition(CGPoint.ccp(centerx, centery));
                float x = s.getWidth() / 2 - zhupaiPos + zhupaiPos * fourm;
                float y = s.getHeight() - cardArr[0].height * 1.5f;
                CCSequence ccSequence = CCSequence.actions(CCDelayTime.action(deltime), CCMoveTo.action(movetime, CGPoint.ccp(x, y)));
                //设置背面
                cardArr[i].setBeiMian(cardBgBitmap);
                cardArr[i].runAction(ccSequence);
                fourm++;
                continue;
            }
            //分别发牌
            if (i % 3 == 0) {
                //左边
                addChild(cardArr[i]);//加入时间
                playerList[0].add(cardArr[i]);
                cardArr[i].setPosition(CGPoint.ccp(centerx, centery));
                float x = cardArr[0].width;
                float y = s.getHeight() - onem * postionMove * 0.34f - cardArr[0].height*0.6f;
                CCSequence ccSequence = CCSequence.actions(CCDelayTime.action(deltime), CCMoveTo.action(movetime, CGPoint.ccp(x, y)));
                //设置背面
                cardArr[i].setBeiMian(cardBgBitmap);
                cardArr[i].runAction(ccSequence);
                onem++;
            } else if (i % 3 == 1) {
                //中间
                addChild(cardArr[i]);//加入时间
                playerList[1].add(cardArr[i]);
                cardArr[i].setPosition(CGPoint.ccp(centerx, centery));
                float x = s.getWidth() / 2 - (17 * postionMove / 2) + twom * postionMove + postionMove / 2;
                float y = cardArr[0].height;
                CCSequence ccSequence = CCSequence.actions(CCDelayTime.action(deltime), CCMoveTo.action(movetime, CGPoint.ccp(x, y)));
                cardArr[i].runAction(ccSequence);
                twom++;
            } else if (i % 3 == 2) {
                //右边
                addChild(cardArr[i]);//加入时间
                playerList[2].add(cardArr[i]);
                cardArr[i].setPosition(CGPoint.ccp(centerx, centery));
                float x = s.getWidth() - cardArr[0].width;
                float y = s.getHeight() - threem * postionMove * 0.34f - cardArr[0].height * 0.6f;
                CCSequence ccSequence = CCSequence.actions(CCDelayTime.action(deltime), CCMoveTo.action(movetime, CGPoint.ccp(x, y)));
                //设置背面
                cardArr[i].setBeiMian(cardBgBitmap);
                cardArr[i].runAction(ccSequence);
                threem++;
            }
        }

    }

    // 洗牌
    public void washCards() {
        // 打乱顺序 随机交换拍牌的位置
        for (int i = 0; i < 100; i++) {
            Random random = new Random();
            int a = random.nextInt(54);
            int b = random.nextInt(54);
            Card k = cardArr[a];
            cardArr[a] = cardArr[b];
            cardArr[b] = k;
        }
    }
}
