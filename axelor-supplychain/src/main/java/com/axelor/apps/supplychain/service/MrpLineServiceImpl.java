/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2017 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.supplychain.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

import org.hibernate.proxy.HibernateProxy;

import com.axelor.apps.Pair;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.user.UserService;
import com.axelor.apps.purchase.db.PurchaseOrder;
import com.axelor.apps.purchase.db.PurchaseOrderLine;
import com.axelor.apps.purchase.db.repo.PurchaseOrderRepository;
import com.axelor.apps.purchase.service.PurchaseOrderLineService;
import com.axelor.apps.sale.db.SaleOrderLine;
import com.axelor.apps.stock.db.Location;
import com.axelor.apps.stock.db.StockRules;
import com.axelor.apps.stock.db.repo.StockRulesRepository;
import com.axelor.apps.stock.service.StockRulesService;
import com.axelor.apps.supplychain.db.MrpForecast;
import com.axelor.apps.supplychain.db.MrpLine;
import com.axelor.apps.supplychain.db.MrpLineOrigin;
import com.axelor.apps.supplychain.db.MrpLineType;
import com.axelor.apps.supplychain.db.repo.MrpLineTypeRepository;
import com.axelor.apps.supplychain.exception.IExceptionMessage;
import com.axelor.auth.db.AuditableModel;
import com.axelor.auth.db.User;
import com.axelor.db.Model;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;


public class MrpLineServiceImpl implements MrpLineService  {
	
	protected PurchaseOrderServiceSupplychainImpl purchaseOrderServiceSupplychainImpl;
	protected PurchaseOrderLineService purchaseOrderLineService;
	protected PurchaseOrderRepository purchaseOrderRepo;
	protected StockRulesService stockRulesService;

	protected LocalDate today;
	protected User user;

	@Inject
	public MrpLineServiceImpl(AppBaseService appBaseService, UserService userService, PurchaseOrderServiceSupplychainImpl purchaseOrderServiceSupplychainImpl, 
			PurchaseOrderLineService purchaseOrderLineService, PurchaseOrderRepository purchaseOrderRepo, StockRulesService stockRulesService)  {
		
		this.purchaseOrderServiceSupplychainImpl = purchaseOrderServiceSupplychainImpl;
		this.purchaseOrderLineService = purchaseOrderLineService;
		this.purchaseOrderRepo = purchaseOrderRepo;
		this.stockRulesService = stockRulesService;
		
		this.today = appBaseService.getTodayDate();
		this.user = userService.getUser();
	}

	@Override
	public void generateProposal(MrpLine mrpLine) throws AxelorException  {
		generateProposal(mrpLine, null);
	}

	@Override
	public void generateProposal(MrpLine mrpLine, Map<Pair<Partner, LocalDate>, PurchaseOrder> purchaseOrders) throws AxelorException  {
		
		if(mrpLine.getMrpLineType().getElementSelect() == MrpLineTypeRepository.ELEMENT_PURCHASE_PROPOSAL)  {
			
			this.generatePurchaseProposal(mrpLine, purchaseOrders);
			
		}
		
	}
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	protected void generatePurchaseProposal(MrpLine mrpLine, Map<Pair<Partner, LocalDate>, PurchaseOrder> purchaseOrders) throws AxelorException  {
		
		Product product = mrpLine.getProduct();
		Location location = mrpLine.getLocation();
		LocalDate maturityDate = mrpLine.getMaturityDate();
		
		Partner supplierPartner = product.getDefaultSupplierPartner();

		if(supplierPartner == null)  {  
			throw new AxelorException(String.format(I18n.get(IExceptionMessage.MRP_LINE_1),
					product.getFullName()), IException.CONFIGURATION_ERROR); 
		}

		Company company = location.getCompany();

		Pair<Partner, LocalDate> key = null;
		PurchaseOrder purchaseOrder = null;
	
		if (purchaseOrders != null) {
			key = new Pair<>(supplierPartner, maturityDate);
			purchaseOrder = purchaseOrders.get(key);
		}
		
		if (purchaseOrder == null) {
			purchaseOrder = purchaseOrderRepo.save(purchaseOrderServiceSupplychainImpl.createPurchaseOrder(
					this.user,
					company,
					null,
					supplierPartner.getCurrency(),
					maturityDate,
					"MRP-"+this.today.toString(), //TODO sequence on mrp
					null,
					location,
					this.today,
					supplierPartner.getPurchasePriceList(),
					supplierPartner));
			if (purchaseOrders != null) {
				purchaseOrders.put(key, purchaseOrder);
			}
		}
		Unit unit = product.getPurchasesUnit();
		BigDecimal qty = mrpLine.getQty();
		if(unit == null){
			unit = product.getUnit();
		}
		else{
			qty = Beans.get(UnitConversionService.class).convertWithProduct(product.getUnit(), unit, qty, product);
		}
		purchaseOrder.addPurchaseOrderLineListItem(
				purchaseOrderLineService.createPurchaseOrderLine(
						purchaseOrder,
						product,
						null,
						null,
						qty,
						unit));

		purchaseOrderServiceSupplychainImpl.computePurchaseOrder(purchaseOrder);

		linkToOrder(mrpLine, purchaseOrder);
	}

	protected void linkToOrder(MrpLine mrpLine, AuditableModel order) {
		mrpLine.setProposalSelect(order.getClass().getName());
		mrpLine.setProposalSelectId(order.getId());
		mrpLine.setProposalGenerated(true);
	}
	
	
	public MrpLine createMrpLine(Product product, int maxLevel, MrpLineType mrpLineType, BigDecimal qty, LocalDate maturityDate, BigDecimal cumulativeQty, Location location, Model... models)  {
		
		MrpLine mrpLine = new MrpLine();

		mrpLine.setProduct(product);
		mrpLine.setMaxLevel(maxLevel);
		mrpLine.setMrpLineType(mrpLineType);
		if(mrpLineType.getTypeSelect() == MrpLineTypeRepository.TYPE_OUT)  {
			mrpLine.setQty(qty.negate());
		}
		else  {
			mrpLine.setQty(qty);
		}
		mrpLine.setMaturityDate(maturityDate);
		mrpLine.setCumulativeQty(cumulativeQty);		
		mrpLine.setLocation(location);
		
		mrpLine.setMinQty(this.getMinQty(product, location));
		
		this.createMrpLineOrigins(mrpLine, models);
		
		return mrpLine;
	}
	
	protected BigDecimal getMinQty(Product product, Location location)  {
		
		StockRules stockRules = stockRulesService.getStockRules(product, location, StockRulesRepository.TYPE_FUTURE);
		
		if(stockRules != null)  {
			return stockRules.getMinQty();
		}
		return BigDecimal.ZERO;
		
	}
	
	
	protected void createMrpLineOrigins(MrpLine mrpLine, Model... models)  {
		
		if(models != null)  {
			
			for(Model model : Arrays.asList(models))  {
				
				mrpLine.addMrpLineOriginListItem(this.createMrpLineOrigin(model));
				mrpLine.setRelatedToSelectName(this.computeReleatedName(model));
			}
			
		}
		
	}
	
	public MrpLineOrigin createMrpLineOrigin(Model model)  {
		
		Class<?> klass = model.getClass();
		if ( model instanceof HibernateProxy ) { klass = ( (HibernateProxy) model ).getHibernateLazyInitializer().getPersistentClass(); }
		
		MrpLineOrigin mrpLineOrigin = new MrpLineOrigin();
		mrpLineOrigin.setRelatedToSelect(klass.getCanonicalName());
		mrpLineOrigin.setRelatedToSelectId(model.getId());

		return mrpLineOrigin;
	}
	
	public MrpLineOrigin copyMrpLineOrigin(MrpLineOrigin mrpLineOrigin)  {
		
		MrpLineOrigin copyMrpLineOrigin = new MrpLineOrigin();
		copyMrpLineOrigin.setRelatedToSelect(mrpLineOrigin.getRelatedToSelect());
		copyMrpLineOrigin.setRelatedToSelectId(mrpLineOrigin.getRelatedToSelectId());

		return copyMrpLineOrigin;
	}
	
	protected String computeReleatedName(Model model)  {
		
		if(model instanceof SaleOrderLine)  {
			
			return ((SaleOrderLine) model).getSaleOrder().getSaleOrderSeq();
			
		}
		else if(model instanceof PurchaseOrderLine)  {
			
			return ((PurchaseOrderLine) model).getPurchaseOrder().getPurchaseOrderSeq();
		}
		else if(model instanceof MrpForecast)  {
			
			MrpForecast mrpForecast = (MrpForecast) model;
			return mrpForecast.getId()+"-"+mrpForecast.getForecastDate();
		}
		return null;
	}

}
