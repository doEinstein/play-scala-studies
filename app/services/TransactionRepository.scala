package services

import javax.inject.Singleton

import domain.BankDomain.{BankDB, banks}
import domain.BoletoGatewayDomain._
import domain.BoletoTransactionDomain.{BoletoDB, TransactionDB, _}
import domain.CascadeLogDomain.{CascadeLogDB, CascadeLogItemDB, cascadeLogItems, cascadeLogs}
import domain.EstablishmentDomain.{EstablishmentDB, _}
import domain.NormalizedStatusDomain.{NormalizedStatusDB, normalizedStatus}
import services.RepositoryUtils.db
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

sealed trait TransactionRepository {

  def findById(id: Long)(implicit executionContext: ExecutionContext): Future[Option[Transaction]]
  def findTransactionsByReference(transactionFilters: TransactionFilters)(implicit executionContext: ExecutionContext): Future[Seq[Transaction]]

  def findTransactionsByFilter(transactionFilters: TransactionFilters)(implicit executionContext: ExecutionContext): Future[Seq[TransactionSearchData]]
}

case class TransactionFilters(referenceCode: Option[String], bankNumber: Option[String], establishment: Option[String], bank: Option[String], normalizedStatus: Option[String], status: Option[String], amount: Option[Int])

case class TransactionSearchData(id: Long, referenceCode: String, bankNumber: Option[String], establishment: String, bank: Option[String], status: String, amount: Int)

@Singleton
class TransactionRepositoryImpl extends TransactionRepository {
  override def findTransactionsByReference(transactionFilters: TransactionFilters)(implicit executionContext: ExecutionContext): Future[Seq[Transaction]] = {
    val filterTransaction = transactionFilters.referenceCode.map(r => transactions.filter(_.referenceCode === r)).getOrElse(transactions)
    val filterEstablishment = transactionFilters.establishment.map(e => establishments.filter(_.code === e)).getOrElse(establishments)
    val filterBoletoByBankNumber = transactionFilters.bankNumber.map(bn => boletos.filter(_.bankNumber === bn)).getOrElse(boletos)
    val filterBoleto = transactionFilters.amount.map(a => filterBoletoByBankNumber.filter(_.amount === a)).getOrElse(filterBoletoByBankNumber)
    val filterBank = transactionFilters.bank.map(s => banks.filter(_.code === s)).getOrElse(banks)
    val filterNormalizedStatus = transactionFilters.normalizedStatus.map(n => normalizedStatus.filter(_.code === n)).getOrElse(normalizedStatus)

    val transactionQuery = for {
      (((((((t, e), bo), ba), n), c), ci), p) <- filterTransaction join filterEstablishment on (_.establishmentId === _.id) join filterBoleto on (_._1.boletoId === _.id) join filterBank on (_._1._1.bankId === _.id) join filterNormalizedStatus on (_._1._1._1.normalizedStatusId === _.id) joinLeft cascadeLogs on (_._1._1._1._1.cascadeLogId === _.id) joinLeft cascadeLogItems on (_._2.map(_.id) === _.cascadeLogId) joinLeft payments on (_._1._1._1._1._1._1.id === _.transactionId)
    } yield (t, e, bo, ba, n, c, ci, p)

    val result = transactionQuery.sortBy(t => t._1.id.desc).take(30).result

    db.run(result)
      .map(mapTableRows(_))
      .map {
        _.map { tuple =>
          val establishment = new Establishment(tuple._2)
          val boleto = new Boleto(tuple._3)
          val bank = new Bank(tuple._4)
          val normalizedStatus = new NormalizedStatus(tuple._5)
          val cascadeLog = tuple._6.map(new CascadeLog(_, tuple._7.map(new CascadeLogItem(_))))
          val paymentsList = tuple._8.map(new Payment(_))

          new Transaction(tuple._1, establishment, boleto, Option(bank), Option(normalizedStatus), cascadeLog, paymentsList)
        }
      }
  }

  override def findById(id: Long)(implicit executionContext: ExecutionContext): Future[Option[Transaction]] = {
    val transactionFilter = transactions.filter(_.id === id)

    val transactionQuery = for {
      (((((((t, e), bo), ba), n), c), ci), p) <- transactionFilter join establishments on (_.establishmentId === _.id) join boletos on (_._1.boletoId === _.id) join banks on (_._1._1.bankId === _.id) join normalizedStatus on (_._1._1._1.normalizedStatusId === _.id) joinLeft cascadeLogs on (_._1._1._1._1.cascadeLogId === _.id) joinLeft cascadeLogItems on (_._2.map(_.id) === _.cascadeLogId) joinLeft payments on (_._1._1._1._1._1._1.id === _.transactionId)
    } yield (t, e, bo, ba, n, c, ci, p)

    db.run(transactionQuery.result)
      .map(mapTableRows2(_))
      .map {
        _.map { tuple =>
          val establishment = new Establishment(tuple._2)
          val boleto = new Boleto(tuple._3)
          val bank = new Bank(tuple._4)
          val normalizedStatus = new NormalizedStatus(tuple._5)
          val cascadeLog = tuple._6.map(new CascadeLog(_, tuple._7.map(new CascadeLogItem(_))))
          val paymentsList = tuple._8.map(new Payment(_))

          new Transaction(tuple._1, establishment, boleto, Option(bank), Option(normalizedStatus), cascadeLog, paymentsList)
        }
      }
  }


  override def findTransactionsByFilter(transactionFilters: TransactionFilters)(implicit executionContext: ExecutionContext): Future[Seq[TransactionSearchData]] = {
    val filterTransactionByStatus = transactionFilters.status.map(s => transactions.filter(_.status === s)).getOrElse(transactions)
    val filterTransaction = transactionFilters.referenceCode.map(r => filterTransactionByStatus.filter(_.referenceCode === r)).getOrElse(filterTransactionByStatus)
    val filterEstablishment = transactionFilters.establishment.map(e => establishments.filter(_.code === e)).getOrElse(establishments)
    val filterBoletoByBankNumber = transactionFilters.bankNumber.map(bn => boletos.filter(_.bankNumber === bn)).getOrElse(boletos)
    val filterBoleto = transactionFilters.amount.map(a => filterBoletoByBankNumber.filter(_.amount === a)).getOrElse(filterBoletoByBankNumber)
    val filterBank = transactionFilters.bank.map(s => banks.filter(_.code === s)).getOrElse(banks)

    val transactionQuery = for {
      (((t, e), bo), ba) <- filterTransaction join filterEstablishment on (_.establishmentId === _.id) join filterBoleto on (_._1.boletoId === _.id) join filterBank on (_._1._1.bankId === _.id)
    } yield (t.id, t.referenceCode, t.status, e.code, bo.bankNumber, bo.amount, ba.name)

    db.run(transactionQuery.sortBy(_._1.desc).result)
      .map(_.map(t => TransactionSearchData(t._1, t._2, t._5, t._4, Option(t._7), t._3, t._6)))
  }

  private def mapTableRows(seq: Seq[(TransactionDB, EstablishmentDB, BoletoDB, BankDB, NormalizedStatusDB, Option[CascadeLogDB], Option[CascadeLogItemDB], Option[PaymentDB])]): Seq[(TransactionDB, EstablishmentDB, BoletoDB, BankDB, NormalizedStatusDB, Option[CascadeLogDB], Seq[CascadeLogItemDB], Seq[PaymentDB])] = {
    if (seq.isEmpty) {
      Seq.empty
    } else {
      val transactionsTuplesMap = seq.groupBy(_._1.id)

      transactionsTuplesMap.keys.map { k =>
        val tuples = transactionsTuplesMap(k)
        val head = tuples.head
        val seqCascadeLogItem = tuples.map(_._7).filter(_.isDefined).map(_.get).distinct
        val seqPayment = tuples.map(_._8).filter(_.isDefined).map(_.get).distinct

        (head._1, head._2, head._3, head._4, head._5, head._6, seqCascadeLogItem, seqPayment)
      }.toIndexedSeq
    }
  }

  private def mapTableRows2(seq: Seq[(TransactionDB, EstablishmentDB, BoletoDB, BankDB, NormalizedStatusDB, Option[CascadeLogDB], Option[CascadeLogItemDB], Option[PaymentDB])]): Option[(TransactionDB, EstablishmentDB, BoletoDB, BankDB, NormalizedStatusDB, Option[CascadeLogDB], Seq[CascadeLogItemDB], Seq[PaymentDB])] = {
    if (seq.isEmpty) {
      Option.empty
    } else {
      val head = seq.head

      val seqCascadeLogItem = seq.map(_._7).filter(_.isDefined).map(_.get).distinct
      val seqPayment = seq.map(_._8).filter(_.isDefined).map(_.get).distinct

      Option((head._1, head._2, head._3, head._4, head._5, head._6, seqCascadeLogItem, seqPayment))
    }
  }
}
