package com.sijienet.card.game;

import android.graphics.Bitmap;

import gameview.com.sijienet.com.doudizhugame.CardGameObj;

/*
 * 公共牌模型
 * */
public class Card {//集成类精灵

	public Bitmap bitmap;//图片
	public String name; //Card的名称
	public boolean rear=true;//是否是背面
	public boolean clicked=false;//是否被点击
	public CardGameObj gameObj;

}
