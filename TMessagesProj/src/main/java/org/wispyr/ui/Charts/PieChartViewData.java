package org.wispyr.ui.Charts;

import android.animation.Animator;

import org.wispyr.ui.Charts.data.ChartData;
import org.wispyr.ui.Charts.view_data.StackLinearViewData;

public class PieChartViewData extends StackLinearViewData {

    float selectionA;
    float drawingPart;
    Animator animator;

    public PieChartViewData(ChartData.Line line) {
        super(line);
    }
}
