package com.myCard;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;

import org.cocos2d.actions.interval.CCMoveBy;
import org.cocos2d.nodes.CCSprite;
import org.cocos2d.nodes.CCTextureCache;
import org.cocos2d.types.CGPoint;

/*
 * 公共牌模型
 * */
public class Card extends CCSprite {//集成类精灵

	int x=0;      //横坐标 需要废除
	int y=0;	  //纵坐标 需要废除
	int width;    //宽度
	int height;   //高度
	Bitmap bitmap;//图片 
	String name; //Card的名称
	boolean rear=true;//是否是背面
	boolean clicked=false;//是否被点击

	public Card(int width,int height,Bitmap bitmap){
		//初始化
		this.width=width;
		this.height=height;
		this.bitmap=bitmap;
	}

	public Card(int width,int height,Bitmap bitmap,String name){
		//初始化
		super(bitmap,name);
		this.width=width;
		this.height=height;
		this.bitmap=bitmap;
		this.name=name;
	}

	//构造函数
	public Card() {
		super();
	}

	//设置背面
	public void setBeiMian(Bitmap newbitmap){
		this.rear = true;
		//修改纹理
		setTexture(CCTextureCache.sharedTextureCache().addImage(newbitmap, this.name + "bg"));
	}

	//还原
	public void huanYuan(){
		this.rear=false;
		setTexture(CCTextureCache.sharedTextureCache().addImage(this.bitmap, this.name));
	}

	//点击选中
	public void Mclick(){
		this.clicked=true;
		//向上移动
		CCMoveBy ccMoveBy=CCMoveBy.action(0.07f, CGPoint.ccp(0,height/2));
		runAction(ccMoveBy);
	}

	//还原位置
	public void MReclick(){
		this.clicked=false;
		//向上移动
		CCMoveBy ccMoveBy=CCMoveBy.action(0.07f, CGPoint.ccp(0,-height/2));
		runAction(ccMoveBy);
	}

	//设置层次
	public void setLayerZindex(){
		getZOrder();
	}

	public void setLocation(int x,int y){
		//设置坐标
		this.x=x;
		this.y=y;
	}

	public void setName(String name){
		this.name=name;
	}

	public Rect getSRC(){
		return new Rect(0,0,width,height);

	}


	public Rect getDST(){
		return new Rect(x, y,x+width, y+height);

	}

	@Override
	public String toString() {
		return "Card [name=" + name + "]";

	}


	//设置缩放图片
	public static Bitmap ScaleMyBitmap(Bitmap bitmap,float sxy){
		Matrix matrix = new Matrix();
		matrix.postScale(sxy, sxy); //长和宽放大缩小的比例
		Bitmap resizeBmp = Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
		return resizeBmp;
	}
	
	
	
}
