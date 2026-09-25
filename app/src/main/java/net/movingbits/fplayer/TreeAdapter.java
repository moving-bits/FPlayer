package net.movingbits.fplayer;

import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.google.android.material.color.MaterialColors;

/** Shows the visible nodes of the directory tree as an indented list. */
final class TreeAdapter extends RecyclerView.Adapter<TreeAdapter.Holder> {

    interface Listener {
        void onToggleExpanded(TreeNode node);

        void onToggleSelected(TreeNode node);

        void onTap(TreeNode node);

        void onDoubleTap(TreeNode node);

        void onRemoveRoot(TreeNode root);
    }

    private final Listener listener;
    private List<TreeNode> items = Collections.emptyList();
    private String playingUri;

    private TreeNode lastTapNode;
    private long lastTapTime;

    TreeAdapter(final Listener listener) {
        this.listener = listener;
    }

    @SuppressWarnings("NotifyDataSetChanged")
    void setItems(final List<TreeNode> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @SuppressWarnings("NotifyDataSetChanged")
    void setPlayingUri(final String uri) {
        if (!Objects.equals(playingUri, uri)) {
            playingUri = uri;
            notifyDataSetChanged();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tree, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder h, final int position) {
        final TreeNode node = items.get(position);
        h.node = node;

        // Indent by the width of one icon per level; files have a placeholder instead of the expand
        // icon and are therefore aligned with the name of their directory.
        final int step = h.itemView.getResources().getDimensionPixelSize(R.dimen.tree_icon_width);
        h.itemView.setPaddingRelative(node.depth * step, 0, 0, 0);

        if (node.directory && !node.children.isEmpty()) {
            h.expander.setVisibility(View.VISIBLE);
            h.expander.setImageResource(node.expanded ? R.drawable.ic_expand_more : R.drawable.ic_chevron_right);
            setLabel(h.expander, node.expanded ? R.string.collapse : R.string.expand);
        } else {
            h.expander.setVisibility(View.INVISIBLE);
        }

        final int checkState = node.getCheckState();
        setLabel(h.check, checkState == TreeNode.CHECK_ALL ? R.string.deselect : R.string.select);
        switch (checkState) {
            case TreeNode.CHECK_ALL:
                h.check.setImageResource(R.drawable.ic_check_box);
                break;
            case TreeNode.CHECK_PARTIAL:
                h.check.setImageResource(R.drawable.ic_check_box_indeterminate);
                break;
            default:
                h.check.setImageResource(R.drawable.ic_check_box_outline_blank);
                break;
        }

        h.name.setText(node.name);
        final boolean playing = !node.directory && node.uri.toString().equals(playingUri);
        h.name.setTypeface(null, playing ? Typeface.BOLD : Typeface.NORMAL);
        h.name.setTextColor(MaterialColors.getColor(h.name,
                playing ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurface));

        h.remove.setVisibility(node.isRoot() ? View.VISIBLE : View.GONE);
    }

    /** Sets the accessibility label and the tooltip together. */
    private static void setLabel(final View view, final int stringRes) {
        final CharSequence label = view.getContext().getText(stringRes);
        view.setContentDescription(label);
        view.setTooltipText(label);
    }

    private void onNameClicked(final TreeNode node) {
        final long now = SystemClock.uptimeMillis();
        if (node == lastTapNode && now - lastTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
            lastTapNode = null;
            listener.onDoubleTap(node);
        } else {
            lastTapNode = node;
            lastTapTime = now;
            listener.onTap(node);
        }
    }

    final class Holder extends RecyclerView.ViewHolder {
        final ImageView expander;
        final ImageView check;
        final TextView name;
        final ImageButton remove;
        TreeNode node;

        Holder(final View view) {
            super(view);
            expander = view.findViewById(R.id.expander);
            check = view.findViewById(R.id.check);
            name = view.findViewById(R.id.name);
            remove = view.findViewById(R.id.remove);
            expander.setOnClickListener(v -> listener.onToggleExpanded(node));
            check.setOnClickListener(v -> listener.onToggleSelected(node));
            name.setOnClickListener(v -> onNameClicked(node));
            remove.setOnClickListener(v -> listener.onRemoveRoot(node));
        }
    }
}
