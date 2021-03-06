/*
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cyanogenmod.theme.chooser;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.os.Build;
import android.view.Gravity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.ThemeConfig;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.support.v7.widget.CardView;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.webkit.URLUtil;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import cyanogenmod.providers.ThemesContract;
import cyanogenmod.providers.ThemesContract.ThemesColumns;

import org.cyanogenmod.theme.chooser.WallpaperAndIconPreviewFragment.IconInfo;
import org.cyanogenmod.theme.chooser.WallpaperAndIconPreviewFragment;
import org.cyanogenmod.theme.util.BootAnimationHelper;
import org.cyanogenmod.theme.util.IconPreviewHelper;
import org.cyanogenmod.theme.util.ThemedTypefaceHelper;
import org.cyanogenmod.theme.util.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.cyanogenmod.internal.util.ThemeUtils.SYSTEM_TARGET_API;

public class ChooserBrowseFragment extends Fragment
        implements LoaderManager.LoaderCallbacks<Cursor> {
    public static final String TAG = ChooserBrowseFragment.class.getCanonicalName();
    public static final String DEFAULT = ThemeConfig.SYSTEM_DEFAULT;

    public AbsListView mListView;
    public LocalPagerAdapter mAdapter;
    public ArrayList<String> mComponentFilters;
    public LruCache<String, Bitmap> mHeaderCache;

    private Point mMaxImageSize = new Point(); //Size of preview image in listview

    public static ChooserBrowseFragment newInstance(ArrayList<String> componentFilters, String title) {
        ChooserBrowseFragment fragment = new ChooserBrowseFragment();
        Bundle args = new Bundle();
        args.putStringArrayList(ChooserActivity.EXTRA_COMPONENT_FILTER, componentFilters);
        args.putString(ChooserActivity.EXTRA_TITLE, title);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_chooser_browse, container, false);
        ArrayList<String> filters = getArguments().getStringArrayList(ChooserActivity.EXTRA_COMPONENT_FILTER);
        mComponentFilters = (filters != null) ? filters : new ArrayList<String>(0);
        // If we are filtering by "styles" add status bar and navigation bar to the list
        if (mComponentFilters.contains(ThemesColumns.MODIFIES_OVERLAYS)) {
            mComponentFilters.add(ThemesColumns.MODIFIES_STATUS_BAR);
            mComponentFilters.add(ThemesColumns.MODIFIES_NAVIGATION_BAR);
        }
        mListView = (AbsListView) v.findViewById(R.id.list);
        mAdapter = new LocalPagerAdapter(getActivity(), null, mComponentFilters);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final String pkgName = (String) mAdapter.getItem(position);
                ChooserDetailFragment fragment =  ChooserDetailFragment.newInstance(pkgName,
                        mComponentFilters);
                FragmentTransaction transaction = getFragmentManager().beginTransaction();
                transaction.replace(R.id.content, fragment, ChooserDetailFragment.class.toString());
                transaction.addToBackStack(null);
                transaction.commit();
            }
        });
        getLoaderManager().initLoader(0, null, this);

        Display display = getActivity().getWindowManager().getDefaultDisplay();
        display.getSize(mMaxImageSize);
        mMaxImageSize.y  = (int) getActivity().getResources().getDimension(R.dimen.item_browse_height);
        mMaxImageSize.x  = (int) getActivity().getResources().getDimension(R.dimen.item_browse_width);

        return v;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        String title = getArguments().getString(ChooserActivity.EXTRA_TITLE, getString(R.string.app_name));
        getActivity().setTitle(title);
        // Get memory class of this device, exceeding this amount will throw an
        // OutOfMemory exception.
        final int memClass = ((ActivityManager) getActivity().getSystemService(
                Context.ACTIVITY_SERVICE))
                .getMemoryClass();
        // Use 1/8th of the available memory for this memory cache.
        final int cacheSize = 1024 * 1024 * memClass / 8;
        mHeaderCache = new LruCache<String, Bitmap>(cacheSize) {
            protected int sizeOf(String key, Bitmap bitmap) {
                // The cache size will be measured in bytes rather than number of items.
                return bitmap.getByteCount();
            }
        };
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.chooser_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.get_more_themes) {
            lauchGetThemes();
        }

        return false;
    }
    private void lauchGetThemes() {
        Context context = getActivity();
        String playStoreUrl = context.getResources().getString(R.string.play_store_url);
        String wikiUrl = context.getResources().getString(R.string.wiki_url);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(playStoreUrl));

        // Try to launch play store
        if (intent.resolveActivity(getActivity().getPackageManager()) != null) {
            context.startActivity(intent);
        } else {
            // If no play store, try to open wiki url
            intent.setData(Uri.parse(wikiUrl));
            if (intent.resolveActivity(getActivity().getPackageManager()) != null) {
                context.startActivity(intent);
            } else {
                Toast.makeText(getActivity(), R.string.get_more_app_not_available,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        // Swap the new cursor in. (The framework will take care of closing the
        // old cursor once we return.)
        mAdapter.swapCursor(data);
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String selection;
        String selectionArgs[] = null;
        if (mComponentFilters.isEmpty()) {
            selection = ThemesColumns.PRESENT_AS_THEME + "=?";
            selectionArgs = new String[] {"1"};
        } else {
            StringBuffer sb = new StringBuffer();
            for(int i=0; i < mComponentFilters.size(); i++) {
                sb.append(mComponentFilters.get(i));
                sb.append("=1");
                if (i !=  mComponentFilters.size()-1) {
                    sb.append(" OR ");
                }
            }
            selection = sb.toString();
        }

        // sort in ascending order but make sure the "default" theme is always first
        String sortOrder = "(" + ThemesColumns.IS_DEFAULT_THEME + "=1) DESC, "
                + ThemesColumns.TITLE + " ASC";

        return new CursorLoader(getActivity(), ThemesColumns.CONTENT_URI, null, selection,
                selectionArgs, sortOrder);
    }

    public class LocalPagerAdapter extends CursorAdapter {
        List<String> mFilters;
        Context mContext;
        HashMap<String, ThemedTypefaceHelper> mTypefaceHelpers =
                new HashMap<String, ThemedTypefaceHelper>();

        public LocalPagerAdapter(Context context, Cursor c, List<String> filters) {
            super(context, c, 0);
            mFilters = filters;
            mContext = context;
        }

        @Override
        public Object getItem(int position) {
            mCursor.moveToPosition(position);
            int pkgIdx = mCursor.getColumnIndex(ThemesColumns.PKG_NAME);
            return mCursor.getString(pkgIdx);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            int titleIdx = mCursor.getColumnIndex(ThemesColumns.TITLE);
            int authorIdx = mCursor.getColumnIndex(ThemesColumns.AUTHOR);
            int hsIdx = mCursor.getColumnIndex(ThemesColumns.HOMESCREEN_URI);
            int wpIdx = mCursor.getColumnIndex(ThemesColumns.WALLPAPER_URI);
            int styleIdx = mCursor.getColumnIndex(ThemesColumns.STYLE_URI);
            int pkgIdx = mCursor.getColumnIndex(ThemesColumns.PKG_NAME);
            int defaultIndex = mCursor.getColumnIndex(ThemesColumns.IS_DEFAULT_THEME);
            int targetApiIdx = mCursor.getColumnIndex(ThemesColumns.TARGET_API);

            String pkgName = mCursor.getString(pkgIdx);
            String title = DEFAULT.equals(pkgName) ? mContext.getString(R.string.system_theme_name)
                    : mCursor.getString(titleIdx);
            String author = mCursor.getString(authorIdx);
            String hsImagePath = DEFAULT.equals(pkgName) ? mCursor.getString(hsIdx) :
                    mCursor.getString(wpIdx);
            String styleImagePath = mCursor.getString(styleIdx);
            boolean isDefaultTheme = mCursor.getInt(defaultIndex) == 1;

            ThemeItemHolder item = (ThemeItemHolder) view.getTag();
            item.title.setText(title + (isDefaultTheme ? " "
                    + getString(R.string.default_tag) : ""));
            item.author.setText(author);

            int targetApi = mCursor.getInt(targetApiIdx);
            item.designedFor.setVisibility(
                    (targetApi == SYSTEM_TARGET_API || targetApi > Build.VERSION_CODES.KITKAT) ?
                    View.GONE : View.VISIBLE);

            item.boundPackage = pkgName;

            if (mFilters.isEmpty()) {
                bindDefaultView(item, pkgName, hsImagePath);
            } else if (mFilters.contains(ThemesColumns.MODIFIES_BOOT_ANIM)) {
                bindBootAnimView(item, context, pkgName);
            } else if (mFilters.contains(ThemesColumns.MODIFIES_LAUNCHER)) {
                bindWallpaperView(item, pkgName, hsImagePath);
            } else if (mFilters.contains(ThemesColumns.MODIFIES_FONTS)) {
                bindFontView(view, context, pkgName);
            } else if (mFilters.contains(ThemesColumns.MODIFIES_OVERLAYS)) {
                bindOverlayView(item, pkgName, styleImagePath);
            } else if (mFilters.contains(ThemesColumns.MODIFIES_ICONS)) {
                bindDefaultView(item, pkgName, hsImagePath);
                bindIconView(view, context, pkgName);
            } else if (mFilters.contains(ThemesColumns.MODIFIES_STATUSBAR_HEADERS)) {
                bindHeadersView(view, context, pkgName);
            } else {
                bindDefaultView(item, pkgName, hsImagePath);
            }
        }

        private void bindDefaultView(ThemeItemHolder item, String pkgName,
                                     String hsImagePath) {
            //Do not load wallpaper if we preview icons
            if (mFilters.contains(ThemesColumns.MODIFIES_ICONS)) return;

            item.thumbnail.setImageDrawable(null);
            item.applyThemeColor();

            LoadImage loadImageTask = new LoadImage(item, pkgName, null);
            loadImageTask.execute();
        }

        private void bindOverlayView(ThemeItemHolder item, String pkgName,
                                     String styleImgPath) {
            item.thumbnail.setImageDrawable(null);
            item.applyThemeColor();

            LoadImage loadImageTask = new LoadImage(item, pkgName, styleImgPath);
            loadImageTask.execute();
        }

        private void bindBootAnimView(ThemeItemHolder item, Context context, String pkgName) {
            (new BootAnimationHelper.LoadBootAnimationImage(item.thumbnail, context, pkgName)).execute();
        }

        private void bindWallpaperView(ThemeItemHolder item, String pkgName,
                                       String hsImagePath) {

            item.thumbnail.setImageDrawable(null);
            item.applyThemeColor();

            LoadImage loadImageTask = new LoadImage(item, pkgName, null);
            loadImageTask.execute();
        }

        public void bindFontView(View view, Context context, String pkgName) {
            FontItemHolder item = (FontItemHolder) view.getTag();
            ThemedTypefaceHelper helper;
            if (!mTypefaceHelpers.containsKey(pkgName)) {
                helper = new ThemedTypefaceHelper();
                helper.load(mContext, pkgName);
                mTypefaceHelpers.put(pkgName, helper);
            } else {
                helper = mTypefaceHelpers.get(pkgName);
            }
            Typeface typefaceNormal = helper.getTypeface(Typeface.NORMAL);
            Typeface typefaceBold = helper.getTypeface(Typeface.BOLD);
            item.textView.setTypeface(typefaceNormal);
            item.textViewBold.setTypeface(typefaceBold);
        }

        public void bindIconView(View view, Context context, String pkgName) {
            ThemeItemHolder holder = (ThemeItemHolder) view.getTag();
            LoadIconsTask loadImageTask = new LoadIconsTask(context, pkgName, holder.mIconHolders);
            loadImageTask.execute();
        }

        public void bindHeadersView(View view, Context context, String pkgName) {
            HeadersItemHolder item = (HeadersItemHolder) view.getTag();
            Bitmap b = fetchOrGetBitmap(context, pkgName,
                    ThemesContract.PreviewColumns.HEADER_PREVIEW_1);
            if (b != null) {
                item.header1.setBackground(new BitmapDrawable(b));
            }
        }

        private Bitmap fetchOrGetBitmap(Context context, String packageName, String column) {
            Bitmap b = null;
            String key = getHeaderKey(packageName, column);
            try {
                b = mHeaderCache.get(key);
            } catch (Exception e) {
                // do nothing it hasn't been cached yet
            }
            if (b == null) {
                try {
                    b = Utils.getPreviewBitmap(context, packageName, column);
                } catch (Exception e) {
                    // ThemeProvider is still processing
                }
                if (b != null) {
                    mHeaderCache.put(key, b);
                }
            }
            return b;
        }

        private String getHeaderKey(String pkg, String column) {
            return pkg + "_" + column;
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            if (mComponentFilters.isEmpty()) {
                return newDefaultView(context, cursor, parent);
            } else if (mComponentFilters.contains(ThemesColumns.MODIFIES_FONTS)) {
                return newFontView(context, cursor, parent);
            } else if (mComponentFilters.contains(ThemesColumns.MODIFIES_ICONS)) {
                return newDefaultView(context, cursor, parent);
            } else if (mComponentFilters.contains(ThemesColumns.MODIFIES_STATUSBAR_HEADERS)) {
                return newHeadersView(context, cursor, parent);
            }
            return newDefaultView(context, cursor, parent);
        }

        private View newDefaultView(Context context, Cursor cursor, ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            View row = inflater.inflate(R.layout.item_store_browse, parent, false);
            ThemeItemHolder item = new ThemeItemHolder();
            item.thumbnail = (ImageView) row.findViewById(R.id.image);
            item.title = (TextView) row.findViewById(R.id.title);
            item.author = (TextView) row.findViewById(R.id.author);
            item.designedFor = (TextView) row.findViewById(R.id.designed_for);
            item.mIconHolders = (ViewGroup) row.findViewById(R.id.icon_container);
            item.card = (CardView) row.findViewById(R.id.item_card);
            row.setTag(item);
            row.findViewById(R.id.theme_card).setClipToOutline(true);

            return row;
        }

        private View newFontView(Context context, Cursor cursor, ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            View row = inflater.inflate(R.layout.item_chooser_browse_font, parent, false);
            FontItemHolder item = new FontItemHolder();
            item.textView = (TextView) row.findViewById(R.id.text1);
            item.textViewBold = (TextView) row.findViewById(R.id.text2);
            item.title = (TextView) row.findViewById(R.id.title);
            item.author = (TextView) row.findViewById(R.id.author);
            item.designedFor = (TextView) row.findViewById(R.id.designed_for);
            row.setTag(item);
            row.findViewById(R.id.theme_card).setClipToOutline(true);
            return row;
        }

        private View newHeadersView(Context context, Cursor cursor, ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            View row = inflater.inflate(R.layout.item_chooser_browse_headers, parent, false);
            HeadersItemHolder item = new HeadersItemHolder();
            item.header1 = (ImageView) row.findViewById(R.id.header1);
            item.title = (TextView) row.findViewById(R.id.title);
            item.author = (TextView) row.findViewById(R.id.author);
            item.designedFor = (TextView) row.findViewById(R.id.designed_for);
            row.setTag(item);
            row.findViewById(R.id.theme_card).setClipToOutline(true);
            return row;
        }
    }

    public static class ThemeItemHolder {
        ImageView thumbnail;
        TextView title;
        TextView author;
        TextView designedFor;
        CardView card;
        ViewGroup mIconHolders;

        String boundPackage;

        void applyThemeColor() {
            Resources res = thumbnail.getContext().getResources();
            int cardColor = ThemeColorCache.getVibrantColorWithFallback(boundPackage,
                    res, R.color.card_background_default);
            boolean isLight = Utils.isColorLight(cardColor);

            card.setCardBackgroundColor(cardColor);
            title.setTextColor(res.getColor(
                    isLight ? R.color.title_card_light : R.color.title_card_dark));
            author.setTextColor(res.getColor(
                    isLight ? R.color.author_card_light : R.color.author_card_dark));
            designedFor.setTextColor(res.getColor(
                    isLight ? R.color.designed_for_color_dark : R.color.designed_for_color_light));
        }
    }

    public static class FontItemHolder extends ThemeItemHolder {
        TextView textView;
        TextView textViewBold;
    }

    public static class HeadersItemHolder extends ThemeItemHolder {
        ImageView header1;
    }

    public class LoadImage extends AsyncTask<Object, Void, Bitmap> {
        private ThemeItemHolder holder;
        private String path;
        private String pkgName;

        public LoadImage(ThemeItemHolder holder, String pkgName, String path) {
            this.holder = holder;
            this.path = path;
            this.pkgName = pkgName;
        }

        @Override
        protected Bitmap doInBackground(Object... params) {
            Bitmap bitmap = null;
            Context context = getActivity();

            if (context == null) {
                Log.d(TAG, "Activity was detached, skipping loadImage");
                return null;
            }

            if (DEFAULT.equals(pkgName)) {
                Resources res = context.getResources();
                AssetManager assets = new AssetManager();
                assets.addAssetPath(WallpaperAndIconPreviewFragment.FRAMEWORK_RES);
                Resources frameworkRes = new Resources(assets, res.getDisplayMetrics(),
                        res.getConfiguration());
                bitmap = Utils.decodeResource(frameworkRes,
                        com.android.internal.R.drawable.default_wallpaper,
                        mMaxImageSize.x, mMaxImageSize.y);
            } else {
                if (URLUtil.isAssetUrl(path)) {
                    Context ctx = context;
                    try {
                        ctx = context.createPackageContext(pkgName, 0);
                    } catch (PackageManager.NameNotFoundException e) {

                    }
                    bitmap = Utils.getBitmapFromAsset(ctx, path, mMaxImageSize.x, mMaxImageSize.y);
                } else if (path != null) {
                    bitmap = Utils.decodeFile(path, mMaxImageSize.x, mMaxImageSize.y);
                } else {
                    bitmap = Utils.getPreviewBitmap(context, pkgName,
                                 ThemesContract.PreviewColumns.WALLPAPER_PREVIEW);
                }
            }

            if (bitmap != null) {
                ThemeColorCache.updateFromBitmap(pkgName, bitmap);
            }

            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            if (!TextUtils.equals(pkgName, holder.boundPackage)) {
                return;
            }

            holder.thumbnail.setImageBitmap(result);
            holder.applyThemeColor();
        }
    }


    public static class LoadIconsTask extends AsyncTask<Void, Void, List<IconInfo>> {
        private String mPkgName;
        private Context mContext;
        private ViewGroup mIconViewGroup;

        public LoadIconsTask(Context context, String pkgName, ViewGroup iconViewGroup) {
            mPkgName = pkgName;
            mContext = context.getApplicationContext();
            mIconViewGroup = iconViewGroup;
            mIconViewGroup.setTag(pkgName);
        }

        @Override
        protected List<IconInfo> doInBackground(Void... arg0) {
            List<IconInfo> icons = new ArrayList<IconInfo>();
            IconPreviewHelper helper = new IconPreviewHelper(mContext, mPkgName);

            for (ComponentName component
                    : WallpaperAndIconPreviewFragment.getIconComponents(mContext)) {
                Drawable icon = helper.getIcon(component);
                IconInfo info = new IconInfo(null, icon);
                icons.add(info);
            }

            return icons;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mIconViewGroup.removeAllViews();
        }

        @Override
        protected void onPostExecute(List<IconInfo> icons) {
            if (!mIconViewGroup.getTag().toString().equals(mPkgName) || icons == null) {
                return;
            }

            if (mIconViewGroup.getChildCount() != 0) mIconViewGroup.removeAllViews();

            final int iconPadding =
                    mContext.getResources().getDimensionPixelSize(R.dimen.icon_padding);

            for (IconInfo info : icons) {
                LinearLayout.LayoutParams lparams = new LinearLayout.LayoutParams(0,
                        LayoutParams.WRAP_CONTENT, 1f);
                lparams.weight = 1f / icons.size();

                ImageView iv = new ImageView(mContext);
                iv.setLayoutParams(lparams);
                iv.setImageDrawable(info.icon);
                iv.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
                mIconViewGroup.addView(iv);
            }
        }
    }
}
