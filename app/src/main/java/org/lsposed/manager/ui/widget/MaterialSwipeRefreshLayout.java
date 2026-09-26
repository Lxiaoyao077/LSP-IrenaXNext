package org.lsposed.manager.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.color.MaterialColors;

public class MaterialSwipeRefreshLayout extends SwipeRefreshLayout {

    public MaterialSwipeRefreshLayout(Context context) {
        this(context, null);
    }

    public MaterialSwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // MaterialColors.getColor(view, attr) resolves through
        // MaterialAttributes.resolveTypedValueOrThrow, so an attribute the current theme does not
        // set throws IllegalArgumentException - and this runs from the constructor, before the
        // view is even attached. A missing attribute used to take the whole screen down at
        // inflate time instead of just picking another colour, so both reads carry a fallback.
        //
        // colorPrimary belongs to appcompat: material 1.14 stopped re-exporting it, and its own
        // Theme.Material3 sets the appcompat attribute. colorSurfaceContainer is material's own
        // and is present in 1.14.
        setColorSchemeColors(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary,
                android.graphics.Color.GRAY));
        setProgressBackgroundColorSchemeColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainer,
                android.graphics.Color.TRANSPARENT));
    }
}
