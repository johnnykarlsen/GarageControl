package se.karlsen.garagecontrol;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ScaleDrawable;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.res.ResourcesCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.view.View;
import android.widget.Button;
import android.widget.GridView;

/**
 * Created by johnny on 2016-05-29.
 */
public class KeyAdapter extends BaseAdapter {

    public static final String[] KEYS = {"A", "B", "C", "D", "E", "F", "1", "2", "3", "4", "5", "6", "7", "8", "9", "d", "0", "v"};
    public static final int BLACK = 0;
    public static final int GREY = 1;
    private static final String TAG = "garageControl";
    private Button mForwardButton;

    private Context mContext;

    public KeyAdapter(Context c) {

        mContext = c;
    }

    public int getCount() {

        return KEYS.length;
    }

    public Object getItem(int position) {

        return position;
    }

    public long getItemId(int position) {

        return position;
    }

    public void alterForwardKey(int color) {

        Drawable tmp = new ScaleDrawable(ContextCompat.getDrawable(mContext, R.drawable.ic_forward_black_48dp),Gravity.CENTER,(float) 1,(float) 1);
        if (color == BLACK)
            tmp.setAlpha(255);
        else
            tmp.setAlpha(60);
        tmp.setLevel(5000);
        mForwardButton.setForeground(tmp);
    }

    public View getView(int position, View convertView, ViewGroup parent) {

        Button btn;

        if (convertView == null) {
            btn = new Button(mContext);
            btn.setTextSize((float) 40.0);
            btn.setLayoutParams(parent.getLayoutParams());
            btn.setFocusable(false);
            btn.setClickable(false);
        } else {
            btn = (Button) convertView;
        }
        if (position == 15) {
            //Drawable d = ContextCompat.getDrawable(mContext, R.drawable.ic_backspace_black_48dp);
            Drawable tmp = new ScaleDrawable(ContextCompat.getDrawable(mContext, R.drawable.ic_backspace_black_48dp),Gravity.CENTER,(float) 1,(float) 1);
            tmp.setLevel(5000);
            btn.setForeground(tmp);
        } else if (position == 17) {
            Drawable tmp = new ScaleDrawable(ContextCompat.getDrawable(mContext, R.drawable.ic_forward_black_48dp),Gravity.CENTER,(float) 1,(float) 1);
            //Drawable tmp = ContextCompat.getDrawable(mContext, R.drawable.ic_forward_black_48dp);
            tmp.setLevel(5000);
            tmp.setAlpha(60);
            btn.setForeground(tmp);
            mForwardButton = btn;
        } else
            btn.setText(KEYS[position]);

        btn.setId(position);
        return btn;
    }

}