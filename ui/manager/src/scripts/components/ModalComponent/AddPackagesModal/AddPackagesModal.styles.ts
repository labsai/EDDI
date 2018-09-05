import { CSSProperties } from 'react';

const styles: CSSProperties = {
  header: {
    height: '156px',
    backgroundColor: '#F7F9FB',
  },
  topHeader: {
    paddingTop: '55px',
    display: 'flex',
  },
  title: {
    color: '#16325C',
    fontSize: '28px',
    height: '36px',
    marginLeft: '50px',
  },
  centerFlex: {
    flex: '1',
  },
  button: {
    width: '165px',
    marginRight: '50px',
  },
  createButton: {
    width: '165px',
    marginRight: '10px',
  },
  bottomHeader: {
    marginTop: '42px',
    display: 'flex',
  },
  lastModified: {
    marginRight: '50px',
    color: '#54698D',
    fontSize: '12px',
  },
  packageList: {
    marginLeft: '50px',
    marginRight: '50px',
    paddingBottom: '100px',
  },
  closeContainer: {
    display: 'flex',
    marginTop: '-40px',
  },
  closeContainerCenter: {
    flexGrow: '1',
  },
  close: {
    color: '#FFF',
    cursor: 'pointer',
    fontSize: '40px',
    height: '40px',
  },
  loadingWrapper: {
    alignItems: 'center',
    display: 'flex',
    flex: 1,
    height: '500px',
    justifyContent: 'center',
  },
};
export default styles;
