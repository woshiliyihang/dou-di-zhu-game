package com.myCard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.widget.Toast;

import org.cocos2d.layers.CCScene;
import org.cocos2d.nodes.CCDirector;
import org.cocos2d.opengl.CCGLSurfaceView;

/**
 * Created by jason li on 15/12/4.
 */
public class IndexMian extends Activity {

    private CCGLSurfaceView mGLSurfaceView;
    Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mGLSurfaceView = new CCGLSurfaceView(this);
        CCDirector director = CCDirector.sharedDirector();
        director.attachInView(mGLSurfaceView);
        director.setDeviceOrientation(CCDirector.kCCDeviceOrientationLandscapeLeft);
        setContentView(mGLSurfaceView);


        handler=new MyHead();

        // show FPS
        CCDirector.sharedDirector().setDisplayFPS(false);

        // frames per second
        CCDirector.sharedDirector().setAnimationInterval(1.0f / 30);

        CCScene scene = CCScene.node();
        GameLayer mianLayer=new GameLayer(this,handler);
        scene.addChild(mianLayer);



        // Make the Scene active
        CCDirector.sharedDirector().runWithScene(scene);
    }

    class MyHead extends Handler{

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            if (msg.what==3){
                Bundle bundel=msg.getData();
                AlertDialog.Builder builder=new AlertDialog.Builder(IndexMian.this);
                builder.setMessage(bundel.getString("data"));
                builder.setPositiveButton("再来一次", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        CCScene scene = CCScene.node();
                        GameLayer mianLayer=new GameLayer(IndexMian.this,handler);
                        scene.addChild(mianLayer);
                        // Make the Scene active
                        CCDirector.sharedDirector().runWithScene(scene);
                        dialog.cancel();

                    }
                }).create().show();
            }

            else if (msg.what==4){
                Toast.makeText(getApplicationContext(),(String)msg.obj,Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        CCDirector.sharedDirector().onPause();
    }

    @Override
    public void onResume() {
        super.onResume();

        CCDirector.sharedDirector().onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        CCDirector.sharedDirector().end();
        // CCTextureCache.sharedTextureCache().removeAllTextures();
    }




}
