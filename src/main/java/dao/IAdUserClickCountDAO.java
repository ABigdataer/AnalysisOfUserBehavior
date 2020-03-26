package dao;


import domain.AdUserClickCount;
import domain.Task;

import java.util.List;

/**
 * 任务管理DAO接口
 *
 */
public interface IAdUserClickCountDAO {
	
	void updateBatch(List<AdUserClickCount> adUserClickCounts);

	int findClickCountByMultiKey(String date ,long userid,long adid);
	
}
