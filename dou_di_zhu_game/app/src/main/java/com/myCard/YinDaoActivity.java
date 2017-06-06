package com.myCard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.cocos2d.R;

import java.util.List;
import java.util.Map;

/**
 * Created by jason li on 15/12/15.
 */
public class YinDaoActivity extends Activity implements callBack {

    List<Map<String,String>> paiHang;//排行数据
    Button login,reg,intogame;
    EditText name,passwd;
    LinearLayout oneView,twoView;
    TextView nichengview,jinbiview,jifenview,loginout,mypaihang;
    boolean islogin=false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.yin_dao_xml);

        ServerCon.mback = this;
        ServerCon.context = this;

        login=(Button)findViewById(R.id.login);
        reg=(Button)findViewById(R.id.reg);
        intogame=(Button)findViewById(R.id.intogame);
        name=(EditText)findViewById(R.id.name);
        passwd=(EditText)findViewById(R.id.passwd);

        oneView=(LinearLayout)findViewById(R.id.one_view);
        twoView=(LinearLayout)findViewById(R.id.two_view);

        nichengview=(TextView)findViewById(R.id.nichengview);
        jinbiview=(TextView)findViewById(R.id.jinbiview);
        jifenview=(TextView)findViewById(R.id.jifenview);
        loginout=(TextView)findViewById(R.id.loginout);
        mypaihang=(TextView)findViewById(R.id.my_pai_ming);

        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String nicheng = name.getText().toString().trim();
                String mima = passwd.getText().toString().trim();
                if (mima.equals("") || nicheng.equals("")){
                    ServerCon.msg("必须输入完整！");
                }else {
                    ServerCon.goLogin(nicheng, mima);
                }
            }
        });
        reg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String nicheng = name.getText().toString().trim();
                String mima = passwd.getText().toString().trim();
                if (mima.equals("") || nicheng.equals("")) {
                    ServerCon.msg("必须输入完整！");
                } else {
                    ServerCon.goReg(nicheng, mima);
                }
            }
        });
        loginout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                twoView.setVisibility(ViewGroup.GONE);
                oneView.setVisibility(ViewGroup.VISIBLE);
                mypaihang.setVisibility(ViewGroup.GONE);
                //保存用户名
                SharedPreferences.Editor editor = ServerCon.getConfig().edit();
                editor.putString("nicheng", "");
                editor.apply();
                islogin=false;
            }
        });
        intogame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (islogin){
                    startActivity(new Intent(YinDaoActivity.this,IndexMian.class));
                    finish();
                }else {
                    ServerCon.msg("清先登陆！");
                }

            }
        });


        //获取排行版数据
        ServerCon.getPaiHang();

        //判断是否登录
        String nicheng=ServerCon.getConfig().getString("nicheng","");
        if (nicheng.equals("")){
            twoView.setVisibility(ViewGroup.GONE);
            oneView.setVisibility(ViewGroup.VISIBLE);
        }else {
            ServerCon.getUserInfo(nicheng);
        }
    }

    @Override
    public void message(int what, Object obj) {

        if (what==1){
            //获取排行成功
            paiHang = (List<Map<String,String>>)obj;
            mApr pr=new mApr();
            ((ListView)findViewById(R.id.pai_hang_list)).setAdapter(pr);
        }
        if (what==2){
            //登录成功
            userBean item=(userBean)obj;
            loginView(item);
            //保存用户名
            SharedPreferences.Editor editor=ServerCon.getConfig().edit();
            editor.putString("nicheng",item.getNicheng());
            editor.apply();
        }
        if (what==3){
            //注册成功
            userBean item=(userBean)obj;
            loginView(item);
            //保存用户名
            SharedPreferences.Editor editor=ServerCon.getConfig().edit();
            editor.putString("nicheng",item.getNicheng());
            editor.commit();
        }
        if (what==4){
            //获取用户信息
            userBean item=(userBean)obj;
            loginView(item);
        }
        if (what==5){
            //获取排名
            String nums=(String)obj;
            mypaihang.setVisibility(ViewGroup.VISIBLE);
            mypaihang.setText("我的排名\n"+nums+"名");
        }


    }

    //登陆成功显示
    public void loginView(userBean item){
        islogin=true;
        nichengview.setText("昵称："+item.getNicheng());
        jinbiview.setText("金币：" + item.getJinbi());
        jifenview.setText("积分：" + item.getJifen());
        twoView.setVisibility(ViewGroup.VISIBLE);
        oneView.setVisibility(ViewGroup.GONE);
        //获取排名
        ServerCon.getPaiMing();
    }

    class mApr extends BaseAdapter{
        @Override
        public int getCount() {
            return paiHang.size();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            if (convertView==null){
                convertView= LayoutInflater.from(YinDaoActivity.this).inflate(R.layout.pai_hang_item,null);
            }

            Map<String,String> item = paiHang.get(position);
            ((TextView)convertView.findViewById(R.id.one)).setText((position+1)+" "+item.get("nicheng"));
            ((TextView)convertView.findViewById(R.id.two)).setText(""+item.get("jinbi"));
            ((TextView)convertView.findViewById(R.id.three)).setText(""+item.get("jifen"));
            return convertView;
        }
    }
}
