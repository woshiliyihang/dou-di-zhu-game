package com.myCard;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by jason li on 15/12/15.
 */
public class ServerCon {


    public static callBack mback;
    public static Context context;

    //提示函数
    public static void msg(String msg){
        Toast.makeText(context,msg,Toast.LENGTH_SHORT).show();
    }

    //用户配置
    public static SharedPreferences getConfig(){
        return context.getSharedPreferences("config",Context.MODE_PRIVATE);
    }

    //获取排行版数据
    public static void getPaiHang(){
        List<Map<String,String>> re = new ArrayList<Map<String, String>>();
        //测试数据
        for (int i=0; i<100; i++){
            Map<String,String> item = new HashMap<String,String>();
            item.put("nicheng","昵称"+i);
            item.put("jinbi",""+i);
            item.put("jifen",""+i);
            re.add(item);
        }
        mback.message(1,re);
    }

    //用户登录
    public static void goLogin(String username,String passwrod){
        userBean user=new userBean("小猪",120,1245);
        mback.message(2,user);
    }

    //用户注册
    public static void goReg(String username,String passwrod){
        userBean user=new userBean("小猪",120,1245);
        mback.message(3,user);
    }

    //获取用户信息
    public static void getUserInfo(String username){
        userBean user=new userBean("小猪",120,1245);
        mback.message(4,user);
    }

    //获取排名
    public static void getPaiMing(){
        mback.message(5,"123");
    }




}
