package dao;

import model.AdProvinceTop3;
import model.AdStat;

import java.util.List;

public interface IAdProvinceTop3DAO {

	void updateBatch(List<AdProvinceTop3> adProvinceTop3s);
	
}
