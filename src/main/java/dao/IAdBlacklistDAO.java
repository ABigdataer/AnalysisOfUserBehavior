package dao;

import model.AdBlacklist;

import java.util.List;

public interface IAdBlacklistDAO {

	void insertBatch(List<AdBlacklist> adBlacklists);

	List<AdBlacklist> findAll();
	
}
