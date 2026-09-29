package org.wispyr.ui.community.sheet;

import static org.wispyr.messenger.AndroidUtilities.dp;
import static org.wispyr.messenger.LocaleController.getString;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.wispyr.messenger.AndroidUtilities;
import org.wispyr.messenger.ChatObject;
import org.wispyr.messenger.DialogObject;
import org.wispyr.messenger.R;
import org.wispyr.tgnet.TLRPC;
import org.wispyr.ui.ActionBar.Theme;
import org.wispyr.ui.Components.AvatarDrawable;
import org.wispyr.ui.Components.BackupImageView;
import org.wispyr.ui.Components.BottomSheetWithRecyclerListView;
import org.wispyr.ui.Components.ColoredImageSpan;
import org.wispyr.ui.Components.LayoutHelper;
import org.wispyr.ui.Components.RecyclerListView;
import org.wispyr.ui.Components.UItem;
import org.wispyr.ui.Components.UniversalAdapter;
import org.wispyr.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

public class CommunityInviteOnlySheet extends BottomSheetWithRecyclerListView {
    private CommunityPendingInviteOnlyCell cell;
    private ButtonWithCounterView cancelButton;
    private ButtonWithCounterView messageButton;
    private UniversalAdapter adapter;

    public CommunityInviteOnlySheet(Context context, TLRPC.Chat chat, TLRPC.User user, Runnable onUserOpen) {
        super(context, null, false, true, false, false, false, ActionBarType.SLIDING, null);
        headerMoveTop = dp(30);

        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, AndroidUtilities.navigationBarHeight + dp(12 + 48 + 10 + 48 + 12));
        recyclerListView.setClipToPadding(false);

        cancelButton = new ButtonWithCounterView(context, resourcesProvider);
        cancelButton.setText(getString(R.string.Cancel));
        cancelButton.setNeutral();
        cancelButton.setRound();
        cancelButton.setOnClickListener(v -> dismiss());

        final boolean isChannel = ChatObject.isChannelAndNotMegaGroup(chat);

        messageButton = new ButtonWithCounterView(context, resourcesProvider);
        messageButton.setText(getString(isChannel ?
            R.string.CommunityInviteOnlyChannelMessageOwner :
            R.string.CommunityInviteOnlyGroupMessageOwner));
        messageButton.setRound();
        messageButton.setOnClickListener(v -> {
            onUserOpen.run();
            dismiss();
        });

        cell = new CommunityPendingInviteOnlyCell(context);
        cell.setPadding(dp(20), 0, dp(20), dp(17));
        cell.avatarImage.setForUserOrChat(chat, new AvatarDrawable(chat));
        cell.titleView.setText(DialogObject.getName(chat));
        cell.titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append("* ");
        ssb.setSpan(new ColoredImageSpan(R.drawable.mini_ephemeral_hidden_14), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.append(getString(isChannel ?
            R.string.CommunityInviteOnlyChannelInfo :
            R.string.CommunityInviteOnlyGroupInfo
        ));
        cell.textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        cell.textView.setText(ssb);

        containerView.addView(messageButton, LayoutHelper.createFrameMarginPx(
            LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM,
            dp(12) + backgroundPaddingLeft, 0,
            dp(12) + backgroundPaddingLeft,
            dp(12 + 48 + 10) + AndroidUtilities.navigationBarHeight));

        containerView.addView(cancelButton, LayoutHelper.createFrameMarginPx(
            LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM,
            dp(12) + backgroundPaddingLeft, 0,
            dp(12) + backgroundPaddingLeft,
            dp(12) + AndroidUtilities.navigationBarHeight));

        adapter.update(false);
    }

    @Override
    protected CharSequence getTitle() {
        return null;
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(recyclerListView, getContext(), currentAccount, 0, true, this::fillItems, resourcesProvider);
        adapter.setApplyBackground(false);
        return adapter;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustom(0, cell));
    }



    private static class CommunityPendingInviteOnlyCell extends LinearLayout {
        private final BackupImageView avatarImage;
        private final TextView titleView;
        private final TextView textView;

        public CommunityPendingInviteOnlyCell(Context context) {
            super(context);
            setOrientation(LinearLayout.VERTICAL);

            avatarImage = new BackupImageView(context);
            avatarImage.setRoundRadius(dp(35));
            addView(avatarImage, LayoutHelper.createLinear(70, 70, Gravity.CENTER_HORIZONTAL));

            titleView = new TextView(context);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            titleView.setGravity(Gravity.CENTER);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY, 0, 11.33f, 0, 1 + 6));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setLineSpacing(dp(2), 1);
            textView.setGravity(Gravity.CENTER);
            addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }
}
