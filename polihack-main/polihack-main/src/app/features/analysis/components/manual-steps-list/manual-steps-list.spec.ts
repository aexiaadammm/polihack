import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ManualStepsList } from './manual-steps-list';

describe('ManualStepsList', () => {
  let component: ManualStepsList;
  let fixture: ComponentFixture<ManualStepsList>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ManualStepsList],
    }).compileComponents();

    fixture = TestBed.createComponent(ManualStepsList);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
