package dao.factory;

import dao.*;
import dao.impl.*;

/**
 * DAO工厂类
 *
 */
public class DAOFactory {

	/**
	 * 获取任务管理DAO
	 * @return DAO
	 */
	public static ITaskDAO getTaskDAO() {
		return new TaskDAOImpl();
	}

	/**
	 * 将聚合统计结果插入数据库
	 */
	public static ISessionAggrStatDAO getSessionAggrStatDAO()
	{
		return new SessionAggrStatDAOImpl();
	}

	public static ISessionRandomExtractDAO getSessionRandomExtractDAO()
	{
		return new SessionRandomExtractDAOImpl();
	}

	public static ISessionDetailDAO getSessionDetailDAO()
	{
		return new SessionDetailDAOImpl();
	}
	public static ITop10CategoryDAO getTop10CategoryDAO()
	{
		return new Top10CategoryDAOImpl();
	}

	public static  ITop10SessionDAO getTop10SessionDAO()
	{
		return new Top10SessionDAOImpl();
	}
	public static PageSplitConvertRateImpl getPageSplitConvertRateDAO()
	{
		return new PageSplitConvertRateImpl();
	}

	public static AreaTop3ProductImpl getAreaTop3ProductDAO()
	{
		return new AreaTop3ProductImpl();
	}

	public static AdUserClickCountImpl getAdUserClickCountDAO()
	{
		return new AdUserClickCountImpl();
	}

	public static AdBlacklistImpl getAdBlacklistDAO()
	{
		return new AdBlacklistImpl();
	}

	public  static AdStatImpl getAdStatDAO()
	{
		return new AdStatImpl();
	}

	public static AdProvinceTop3Impl getAdProvinceTop3DAO()
	{
		return new AdProvinceTop3Impl();
	}

	public static AdClickTrendImpl getAdClickTrendDAO()
	{
		return new AdClickTrendImpl();
	}

}
