package dao;

import model.AdStat;

import java.util.List;

public interface IAdStatDAO {

	void updateBatch(List<AdStat> adStats);
	
}
