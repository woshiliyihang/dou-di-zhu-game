package com.myCard;

import java.util.List;
import java.util.Vector;
/*
 * 公共牌类型
 * */
public class Model {
	int count;//手数
	int value;//权值
	//一组牌
	List<String> a1=new Vector<String>(); //单张
	List<String> a2=new Vector<String>(); //对子
	List<String> a3=new Vector<String>(); //3带
	List<String> a123=new Vector<String>(); //连子
	List<String> a112233=new Vector<String>(); //连牌
	List<String> a111222=new Vector<String>(); //飞机
	List<String> a4=new Vector<String>(); //炸弹


	@Override
	public String toString() {
		return "Model{" +
				"count=" + count +
				", value=" + value +
				", a1=" + a1 +
				", a2=" + a2 +
				", a3=" + a3 +
				", a123=" + a123 +
				", a112233=" + a112233 +
				", a111222=" + a111222 +
				", a4=" + a4 +
				'}';
	}
}
