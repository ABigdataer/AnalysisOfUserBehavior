package dao;


import domain.AreaTop3Product;
import java.util.List;

public interface IAreaTop3ProductDAO {
	
	/**
	 *将计算出来的各区域top3热门商品写入MySQL中
	 */
	void insertBatch(List<AreaTop3Product> areaTop3Products);
	
}
