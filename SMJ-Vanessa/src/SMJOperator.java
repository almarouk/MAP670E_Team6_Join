import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class SMJOperator {
	private Table r;
	private Table l;
	private PageManager PageManagerR;
	private int PageinR;
	private PageManager PageManagerL;
	private int PageinL;
	private Comparator<Record> comparator = (r1, r2) -> Integer.compare(Integer.parseInt(r1.getValue(0)),
			Integer.parseInt(r2.getValue(0)));
	private boolean emptyTable;
	private String outputPath;
	long durationSort;
	long durationMerge;

	public SMJOperator(Table t1, Table t2, String outputPath) {
		emptyTable = t1.getNumRecords() == 0 || t2.getNumRecords() == 0;
		if (emptyTable)
			return;
		this.r = t1;
		this.l = t2;
		this.outputPath = outputPath;
	}

	private void sort() {
		String pathSortedR = "temp/sorted_" + r.getTablename() + ".csv";
		String pathSortedL = "temp/sorted_" + l.getTablename() + ".csv";
		SortOperator sortOperatorR = new SortOperator(r, comparator);
		sortOperatorR.externalSort("temp/run_" + r.getTablename(), "temp/merge_" + r.getTablename(), pathSortedR);
		SortOperator sortOperatorL = new SortOperator(l, comparator);
		sortOperatorL.externalSort("temp/run_" + l.getTablename(), "temp/merge_" + l.getTablename(), pathSortedL);
		this.r = new Table("sorted_" + r.getTablename(), pathSortedR);
		this.l = new Table("sorted_" + l.getTablename(), pathSortedL);
	}

	private void createPageManager() {
		this.PageManagerR = new PageManager(r);
		this.PageinR = PageManagerR.getNumPages();
		this.PageManagerL = new PageManager(l);
		this.PageinL = PageManagerL.getNumPages();
	}

	private void merge() {
		if (emptyTable) {
			DiskManager.writeRecordsToDisk(outputPath, new ArrayList<Record>());
			return;
		}
		List<Record> joined = new ArrayList<Record>();
		int RightPointer = 0, LeftPointer = 0;
		int markRecord = -1, markPage = -1;
		int right = 0;
		boolean end = false;
		boolean lock = true;
		boolean firstWrite = true;
		int count = 0;
		int maxRecords = Database.RECORDS_PER_PAGE * Database.NUM_BUFFERS;
		List<Record> PageR = PageManagerR.loadPageToMemory(right);
		for (int x = 0; x < this.PageinL; x++) // Looping over all the left pages starting from zero
		{
			LeftPointer = 0; // in Every left page we start with a left pointer at position zero
			lock = true; // lock is used when we need to turn to another Left Page
			List<Record> PageL = PageManagerL.loadPageToMemory(x); // Locating the LeftPage
			while (end == false && lock == true) // Keep running if it's not done , and when left page still have
													// records
			{
				if (markRecord == -1 && markPage == -1) {
					while (lock && comparator.compare(PageL.get(LeftPointer), PageR.get(RightPointer)) < 0) {
						// Increment the left pointer
						if ((LeftPointer + 1) > PageL.size() - 1) { // But we need to check if this pointer is bigger
																	// than the Page size
							lock = false; // If true we need to move to the next left page thus apply lock so it wont
											// enter another function down
						} else {
							LeftPointer++;
						}
					}
					while (!end && lock && comparator.compare(PageL.get(LeftPointer), PageR.get(RightPointer)) > 0) {

						if ((RightPointer + 1) > PageR.size() - 1) // we need to check if the rightpointer is bigger
																	// than the size of the right page
						{
							right++; // increment the page
							if (right > this.PageinR - 1) // but also check if the page exist
							{
								end = true; // if no we end the program
							} else {
								PageR = PageManagerR.loadPageToMemory(right); // if yes we call this page
								RightPointer = 0; // and set the pointer to zero
							}
						} else {
							RightPointer++;
						}
					}
					if (lock && !end) {
						markPage = right; // set the mark page
						markRecord = RightPointer; // set the mark record
					}
				}
				if (!end && lock && comparator.compare(PageL.get(LeftPointer), PageR.get(RightPointer)) == 0) {
					// merge both
					List<String> resultList = new ArrayList<String>(Arrays.asList(PageL.get(LeftPointer).getValues()));
					resultList.addAll(Arrays.asList(PageR.get(RightPointer).getValues()));
					String[] result = resultList.toArray(new String[0]);
					Record r = new Record(result);
					joined.add(r);
					count++;
					if (count == maxRecords) {
						if (firstWrite) {
							DiskManager.writeRecordsToDisk(outputPath, joined);
							firstWrite = false;
						} else {
							DiskManager.appendRecordsToDisk(outputPath, joined);
						}
						joined.clear();
						count = 0;
					}
					RightPointer++;
					if (RightPointer > PageR.size() - 1) // same as before
					{
						right++;
						if (right > this.PageinR - 1) {
							RightPointer = markRecord; // reset the pointers
							if ((right - 1) != markPage) { // reset the page
								right = markPage;
								PageR = PageManagerR.loadPageToMemory(right); // we reload the page we now need
							}
							LeftPointer++;
							if (LeftPointer > PageL.size() - 1) {
								lock = false;
							}
							markRecord = -1;
							markPage = -1;
						} else {
							PageR = PageManagerR.loadPageToMemory(right);
							RightPointer = 0;
						}

					}
					// return result
				} else {
					if (lock && !end) {
						RightPointer = markRecord; // reset the pointers
						if (right != markPage) { // reset the page
							right = markPage;
							PageR = PageManagerR.loadPageToMemory(right); // we reload the page we now need
						}
						LeftPointer++;
						if (LeftPointer > PageL.size() - 1) {
							lock = false;
						}
						markRecord = -1;
						markPage = -1;
					}

				}
			}
			if (end) {
				break;
			}
		}
		if (!joined.isEmpty()) {
			if (firstWrite) {
				DiskManager.writeRecordsToDisk(outputPath, joined);
			} else {
				DiskManager.appendRecordsToDisk(outputPath, joined);
			}
		}
	}

	public void join() {
		long startTime = System.currentTimeMillis();
		sort();
		long endTime = System.currentTimeMillis();
		durationSort = endTime - startTime;
		startTime = System.currentTimeMillis();
		createPageManager();
		merge();
		endTime = System.currentTimeMillis();
		durationMerge = endTime - startTime;
	}

}
