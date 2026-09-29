package org.wispyr.ui.Charts.view_data;

import android.graphics.Paint;

import org.wispyr.ui.Charts.BaseChartView;
import org.wispyr.ui.Charts.data.ChartData;

public class StackLinearViewData extends LineViewData {

    public StackLinearViewData(ChartData.Line line) {
        super(line, false);
        paint.setStyle(Paint.Style.FILL);
        if (BaseChartView.USE_LINES) {
            paint.setAntiAlias(false);
        }
    }
}
