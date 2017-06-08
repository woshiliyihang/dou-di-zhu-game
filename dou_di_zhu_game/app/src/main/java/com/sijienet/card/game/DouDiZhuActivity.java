package com.sijienet.card.game;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.util.List;
import java.util.Random;
import java.util.Vector;

import gameview.com.sijienet.com.androidgameviewbase.Bins;
import gameview.com.sijienet.com.doudizhugame.DouDiZhuGameView;
import gameview.com.sijienet.com.doudizhugame.R;

/**
 * Created by user on 2017/6/5.
 */
public class DouDiZhuActivity extends Activity {

    public static final String tag="DouDiZhuActivity";

    public List<Card> playerList[] = new Vector[3];//三方的纸牌合集
    public List<Card> outList[] = new Vector[3];// 已出牌表
    public Card[] cardArr = new Card[54];//所有纸牌合集
    public List<Card> dizhuList = new Vector<Card>();//地主牌
    public int[] flag = new int[3];// 判断当前是否要牌
    public Context context;
    public Bitmap dzImg;
    public Bitmap bgImg;
    public Bitmap rearBitmap;
    public Handler handler;
    public boolean isRuns;//结束循环胜利控制

    public int imgWidth;

    public DouDiZhuGameView douDiZhuGameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        isRuns=false;
        context = getApplicationContext();
        handler = new Handler();

        //初始化已出牌合集
        for (int i = 0; i < 3; i++) {
            outList[i] = new Vector<Card>();
        }

        //创建三牌的集合对象
        for (int i = 0; i < 3; i++) {
            playerList[i] = new Vector<Card>();
        }

        Common.cards=playerList;

        imgWidth = Bins.dp2px(this,41);

        //初始化牌对象
        initCardArr();

        //洗牌
        washCards();

        //发牌
        pushCard();

        //排序所有牌
        for (int i = 0; i < 3; i++) {
            Common.setOrder(playerList[i]);
        }

        //// TODO: 2017/6/6 抢地主
        douDiZhuGameView = new DouDiZhuGameView(this);
        setContentView(douDiZhuGameView);
        douDiZhuGameView.douDiZhuActivity=this;

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Common.cards=null;
        handler.removeCallbacksAndMessages(null);
        douDiZhuGameView.handler.removeCallbacksAndMessages(null);
    }

    //抢地主
    public void qiangDiZhu(boolean isQiang) {
        if (isQiang)
        {
            Common.dizhuFlag=1;
        }else {
            Common.dizhuFlag= Common.getBestDizhuFlag();
        }
        Common.currentFlag= Common.dizhuFlag;
        Log.i(tag,"地主id=="+ Common.dizhuFlag);
        //地主牌明牌
        for (Card card : dizhuList) {
            card.rear=false;
        }
        playerList[Common.dizhuFlag].addAll(dizhuList);
        for (Card card : dizhuList) {
            Log.i(tag,"地主牌==="+card.name);
        }
        dizhuList.clear();
        Common.setOrder(playerList[Common.dizhuFlag]);
        //不是用户都是背面
        if (Common.dizhuFlag!=1)
        {
            for (Card card : playerList[Common.dizhuFlag]) {
                card.rear=true;
            }
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startLoop();
            }
        },1700);
    }

    private void startLoop() {
        if (isRuns)
        {
            return;
        }
        if (Common.currentFlag==0)
        {
            leftUser();
        }
        if (Common.currentFlag==1)
        {
            centerUser();
        }
        if (Common.currentFlag==2)
        {
            rightUser();
        }
        win();
    }

    private void rightUser() {
        List<Card> card=null;
        Common.currentFlag=2;
        if (flag[0] == 0 && flag[1] == 0) {
            card = Common.getBestAI(playerList[Common.currentFlag], null);
        } else if (flag[1] == 0) {
            Common.oppoerFlag = 0;
            card = Common.getBestAI(playerList[Common.currentFlag], outList[Common.oppoerFlag]);
        } else {
            Common.oppoerFlag = 1;
            card = Common.getBestAI(playerList[Common.currentFlag], outList[Common.oppoerFlag]);
        }
        chuPai(card,0);
    }

    private void centerUser() {
        List<Card> card=null;
        Common.currentFlag=1;
        if (flag[0] == 0 && flag[2] == 0) {
            card = Common.getBestAI(playerList[Common.currentFlag], null);
        } else if (flag[2] == 0) {
            Common.oppoerFlag = 0;
            card = Common.getBestAI(playerList[Common.currentFlag], outList[Common.oppoerFlag]);
        } else {
            Common.oppoerFlag = 2;
            card = Common.getBestAI(playerList[Common.currentFlag], outList[Common.oppoerFlag]);
        }
        chuPai(card,2);
    }

    private void leftUser() {
        List<Card> card=null;
        Common.currentFlag=0;
        if (flag[1] == 0 && flag[2] == 0) {
            card = Common.getBestAI(playerList[Common.currentFlag], null);
        } else if (flag[2] == 0) {
            Common.oppoerFlag = 1;
            card = Common.getBestAI(playerList[Common.currentFlag], outList[Common.oppoerFlag]);
        } else {
            Common.oppoerFlag = 2;
            card = Common.getBestAI(playerList[Common.currentFlag], outList[Common.oppoerFlag]);
        }
        //清空出牌
        chuPai(card,1);
    }

    private void chuPai(List<Card> card, final int nextId) {
        Log.i(tag,"用户==="+ Common.currentFlag);
        outList[Common.currentFlag].clear();
        if (card!=null) {
            flag[Common.currentFlag]=1;
            outList[Common.currentFlag].addAll(card);
            playerList[Common.currentFlag].removeAll(card);
            Common.setOrder(playerList[Common.currentFlag]);
            //// TODO: 2017/6/5 移除已经出去的牌
            StringBuilder builder=new StringBuilder();
            for (Card card1 : playerList[Common.currentFlag]) {
                builder.append(card1.name+",");
            }
            Log.i(tag,"还剩余牌==="+builder.toString());
            StringBuffer buffer=new StringBuffer();
            for (Card card1 : outList[Common.currentFlag]) {
                buffer.append(card1.name+",");
            }
            Log.i(tag,"出牌==="+buffer.toString());
        }else {
            flag[Common.currentFlag]=0;
            //// TODO: 2017/6/5 不要牌
            StringBuilder builder=new StringBuilder();
            for (Card card1 : playerList[Common.currentFlag]) {
                builder.append(card1.name+",");
            }
            Log.i(tag,"还剩余牌==="+builder.toString());
            Log.i(tag,"不要牌");
        }
        //// TODO: 2017/6/5 定时执行重复好书
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Common.currentFlag=nextId;
                startLoop();
            }
        },1700);
    }

    //胜利判断
    private void win() {
        String outmsg=null;

        //判断牌出完了没有
        if (playerList[0].size()<=0){//左边赢了
            if (Common.dizhuFlag==0){
                outmsg="地主胜利";
            }else if (Common.dizhuFlag==1){
                outmsg="你输了，农民胜利";
            }else if (Common.dizhuFlag==2){
                outmsg="您的队友赢了，农民胜利";
            }
        }else if (playerList[1].size()<=0){//我赢了
            if (Common.dizhuFlag==0){
                outmsg="我赢了，农民胜利";
            }else if (Common.dizhuFlag==1){
                outmsg="地主胜利";
            }else if (Common.dizhuFlag==2){
                outmsg="我赢了，农民胜利";
            }
        }else if (playerList[2].size()<=0){//右边赢了
            if (Common.dizhuFlag==0){
                outmsg="您的队友赢了，农民胜利";
            }else if (Common.dizhuFlag==1){
                outmsg="你输了,农民胜利";
            }else if (Common.dizhuFlag==2){
                outmsg="地主胜利";
            }
        }

        if (outmsg==null){//不发信息
            return;
        }

        isRuns=true;//游戏停止
        Log.i(tag,"游戏结束,"+outmsg);
    }

    //发牌
    private void pushCard() {
        for (int i = 0; i < cardArr.length; i++) {
            if (i>50)
            {
                //加入地主的牌
                dizhuList.add(cardArr[i]);
                cardArr[i].rear=true;
                continue;
            }
            //分别发给三端
            if (i%3==0)
            {
                //左边
                playerList[0].add(cardArr[i]);
                cardArr[i].rear=true;
            }
            if (i%3==1)
            {
                //中间
                playerList[1].add(cardArr[i]);
                cardArr[i].rear=false;
            }
            if (i%3==2)
            {
                //右边
                playerList[2].add(cardArr[i]);
                cardArr[i].rear=true;
            }
        }
    }

    // 洗牌
    public void washCards() {
        // 打乱顺序 随机交换拍牌的位置
        for (int i = 0; i < 100; i++) {
            Random random = new Random(System.currentTimeMillis()+i);
            int a = random.nextInt(54);
            int b = random.nextInt(54);
            Card k = cardArr[a];
            cardArr[a] = cardArr[b];
            cardArr[b] = k;
        }
    }

    //初始化牌对象
    private void initCardArr() {
        // 遍历牌
        int count = 0;//卡牌 index
        ApplicationInfo appInfo = context.getApplicationInfo();// 获取应用名称
        for (int i = 1; i <= 4; i++) {// 四种花色
            for (int j = 3; j <= 15; j++) {// 15张牌
                // 根据名字找出ID
                String name = "a" + i + "_" + j;
                int id = context.getResources().getIdentifier(name, "drawable", appInfo.packageName);// 获取资源id
                Bitmap bitmap = Bins.getScaleBitmap(this,id,imgWidth);
                cardArr[count] =new Card();
                cardArr[count].name=name;
                cardArr[count].bitmap=bitmap;
                cardArr[count].rear=false;
                cardArr[count].clicked=false;
                count++;
            }
        }
        //放入大小王
        Bitmap bitmap = Bins.getScaleBitmap(this,R.drawable.a5_16,imgWidth);
        cardArr[52] =new Card();
        cardArr[52].clicked=false;
        cardArr[52].rear=false;
        cardArr[52].bitmap=bitmap;
        cardArr[52].name="a5_16";
        Bitmap bitmap2 = Bins.getScaleBitmap(this,R.drawable.a5_17,imgWidth);
        cardArr[53] =new Card();
        cardArr[53].clicked=false;
        cardArr[53].rear=false;
        cardArr[53].bitmap=bitmap;
        cardArr[53].name="a5_17";

        //地主图标
        dzImg = BitmapFactory.decodeResource(context.getResources(), R.drawable.image0);

        //背景图片
        bgImg = BitmapFactory.decodeResource(context.getResources(), R.drawable.bg_card);

        //纸牌背面
        rearBitmap = Bins.getScaleBitmap(this,R.drawable.cardbg1,imgWidth);
    }

    @Override
    public void onBackPressed() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("weather://sijienet.com/TianQiMain")));
        super.onBackPressed();
    }
}
