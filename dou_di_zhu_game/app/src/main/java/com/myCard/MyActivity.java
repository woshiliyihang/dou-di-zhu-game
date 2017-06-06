package com.myCard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Window;
import android.view.WindowManager;

public class MyActivity extends Activity {
	/*
	 * 默认主界面
	 * */
	MyView myView;
	String messString;
	Handler handler;
	
	class mHead extends Handler{
		
		public void handleMessage(Message msg) {
			if(msg.what==0){
				messString=msg.getData().getString("data");
				showDialog();
			}
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		//getWindow().setFormat(PixelFormat.RGBA_8888);//设置像素格式
		// 隐藏标题栏
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		// 隐藏状态栏
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
		// 锁定横屏
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		handler=new mHead();
		myView = new MyView(this,handler);
		setContentView(new MyView(this,handler));
	}

	public void showDialog(){
		new AlertDialog.Builder(this).setMessage(messString)
		.setPositiveButton("重新开始游戏", new DialogInterface.OnClickListener() {
			
			@Override
			public void onClick(DialogInterface dialog, int which) {
				reGame();
			}
		}).create().show();
	}

	//重新开始游戏
	public void reGame(){
		myView = new MyView(this,handler);
		setContentView(myView);
	}

}
