package com.sijienet.card.game;

import android.graphics.Bitmap;

/*
 * 公共牌模型
 * */
public class Card {//集成类精灵

	public int x=0;      //横坐标 需要废除
	public int y=0;	  //纵坐标 需要废除
	public int width;    //宽度
	public int height;   //高度
	public Bitmap bitmap;//图片
	public String name; //Card的名称
	public boolean rear=true;//是否是背面
	public boolean clicked=false;//是否被点击

}
