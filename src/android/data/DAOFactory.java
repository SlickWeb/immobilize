package au.com.cathis.plugins.location.data;

import android.content.Context;
import au.com.cathis.plugins.location.data.sqlite.SQLiteLocationDAO;

public abstract class DAOFactory {
	public static LocationDAO createLocationDAO(Context context) {
		//Very basic for now
		return new SQLiteLocationDAO(context);
	}
}
