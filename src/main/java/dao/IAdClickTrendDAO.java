package dao;


import model.AdClickTrend;

import java.util.List;

public interface IAdClickTrendDAO {

	void updateBatch(List<AdClickTrend> adClickTrends);
	
}
