/*
 * ttrss-reader-fork for Android
 * 
 * Copyright (C) 2010 N. Braden.
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package org.ttrssreader.model;

import org.ttrssreader.controllers.Controller;
import org.ttrssreader.controllers.DBHelper;
import org.ttrssreader.controllers.Data;
import org.ttrssreader.utils.Utils;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class FeedHeadlineCursorHelper extends MainCursorHelper {
    
    protected static final String TAG = FeedHeadlineCursorHelper.class.getSimpleName();
    
    public FeedHeadlineCursorHelper(Context context, int feedId, int categoryId, boolean selectArticlesForCategory) {
        super(context);
        this.feedId = feedId;
        this.categoryId = categoryId;
        this.selectArticlesForCategory = selectArticlesForCategory;
    }
    
    @Override
    public Cursor createCursor(SQLiteDatabase db, boolean overrideDisplayUnread, boolean buildSafeQuery) {
        
        String query;
        if (feedId > -10)
            query = buildFeedQuery(overrideDisplayUnread, buildSafeQuery);
        else
            query = buildLabelQuery(overrideDisplayUnread, buildSafeQuery);
        
        return db.rawQuery(query, null);
    }
    
    private String buildFeedQuery(boolean overrideDisplayUnread, boolean buildSafeQuery) {
        String lastOpenedArticlesList = Utils.separateItems(Controller.getInstance().lastOpenedArticles, ",");
        
        boolean displayUnread = Controller.getInstance().onlyUnread();
        boolean invertSortArticles = Controller.getInstance().invertSortArticlelist();
        
        if (overrideDisplayUnread)
            displayUnread = false;
        
        StringBuilder query = new StringBuilder();
        query.append("SELECT a._id AS _id, a.feedId AS feedId, a.title AS title, a.isUnread AS unread, a.updateDate AS updateDate, a.isStarred AS isStarred, a.isPublished AS isPublished FROM ");
        query.append(DBHelper.TABLE_ARTICLES);
        query.append(" a, ");
        query.append(DBHelper.TABLE_FEEDS);
        query.append(" b WHERE a.feedId=b._id");
        
        switch (feedId) {
            case Data.VCAT_STAR:
                query.append(" AND a.isStarred=1");
                break;
            
            case Data.VCAT_PUB:
                query.append(" AND a.isPublished=1");
                break;
            
            case Data.VCAT_FRESH:
                query.append(" AND a.updateDate>");
                query.append(System.currentTimeMillis() - Controller.getInstance().getFreshArticleMaxAge());
                query.append(" AND a.isUnread>0");
                break;
            
            case Data.VCAT_ALL:
                query.append(displayUnread ? " AND a.isUnread>0" : "");
                break;
            
            default:
                // User selected to display all articles of a category directly
                query.append(selectArticlesForCategory ? (" AND b.categoryId=" + categoryId)
                        : (" AND a.feedId=" + feedId));
                query.append(displayUnread ? " AND a.isUnread>0" : "");
        }
        
        if (lastOpenedArticlesList.length() > 0 && !buildSafeQuery) {
            query.append(" UNION SELECT c._id AS _id, c.feedId AS feedId, c.title AS title, c.isUnread AS unread, c.updateDate AS updateDate, c.isStarred AS isStarred, c.isPublished AS isPublished FROM ");
            query.append(DBHelper.TABLE_ARTICLES);
            query.append(" c, ");
            query.append(DBHelper.TABLE_FEEDS);
            query.append(" d WHERE c.feedId=d._id AND c._id IN (");
            query.append(lastOpenedArticlesList);
            query.append(" )");
        }
        
        query.append(" ORDER BY a.updateDate ");
        query.append(invertSortArticles ? "ASC" : "DESC");
        query.append(" LIMIT 600 ");
        return query.toString();
    }
    
    private String buildLabelQuery(boolean overrideDisplayUnread, boolean buildSafeQuery) {
        String lastOpenedArticlesList = Utils.separateItems(Controller.getInstance().lastOpenedArticles, ",");
        
        boolean displayUnread = Controller.getInstance().onlyUnread();
        boolean invertSortArticles = Controller.getInstance().invertSortArticlelist();
        
        if (overrideDisplayUnread)
            displayUnread = false;
        
        StringBuilder query = new StringBuilder();
        query.append("SELECT a._id AS _id, a.feedId AS feedId, a.title AS title, a.isUnread AS unread, a.updateDate AS updateDate, a.isStarred AS isStarred, a.isPublished AS isPublished FROM ");
        query.append(DBHelper.TABLE_ARTICLES);
        query.append(" a, ");
        query.append(DBHelper.TABLE_ARTICLES2LABELS);
        query.append(" a2l, ");
        query.append(DBHelper.TABLE_FEEDS);
        query.append(" l WHERE a._id=a2l.articleId AND a2l.labelId=l._id");
        query.append(" AND a2l.labelId=" + feedId);
        query.append(displayUnread ? " AND isUnread>0" : "");
        
        if (lastOpenedArticlesList.length() > 0 && !buildSafeQuery) {
            query.append(" UNION SELECT b._id AS _id, b.feedId AS feedId, b.title AS title, b.isUnread AS unread, b.updateDate AS updateDate, b.isStarred AS isStarred, b.isPublished AS isPublished FROM ");
            query.append(DBHelper.TABLE_ARTICLES);
            query.append(" b, ");
            query.append(DBHelper.TABLE_ARTICLES2LABELS);
            query.append(" b2m, ");
            query.append(DBHelper.TABLE_FEEDS);
            query.append(" m WHERE b2m.labelId=m._id AND b2m.articleId=b._id");
            query.append(" AND b._id IN (");
            query.append(lastOpenedArticlesList);
            query.append(" )");
        }
        
        query.append(" ORDER BY updateDate ");
        query.append(invertSortArticles ? "ASC" : "DESC");
        query.append(" LIMIT 600 ");
        return query.toString();
    }
    
}
