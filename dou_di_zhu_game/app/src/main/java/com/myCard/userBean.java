package com.myCard;

/**
 * Created by jason li on 15/12/15.
 */
public class userBean {

    String nicheng;
    int jinbi;
    int jifen;

    public userBean(String nicheng, int jinbi, int jifen) {
        this.nicheng = nicheng;
        this.jinbi = jinbi;
        this.jifen = jifen;
    }

    public String getNicheng() {
        return nicheng;
    }

    public void setNicheng(String nicheng) {
        this.nicheng = nicheng;
    }

    public int getJinbi() {
        return jinbi;
    }

    public void setJinbi(int jinbi) {
        this.jinbi = jinbi;
    }

    public int getJifen() {
        return jifen;
    }

    public void setJifen(int jifen) {
        this.jifen = jifen;
    }

    @Override
    public String toString() {
        return "userBean{" +
                "nicheng='" + nicheng + '\'' +
                ", jinbi=" + jinbi +
                ", jifen=" + jifen +
                '}';
    }
}
