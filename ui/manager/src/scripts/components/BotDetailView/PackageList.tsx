import * as React from 'react';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import PackageContainer from './PackageContainer';
import Package from './Package';
import { CSSProperties } from 'react';
import { Component, compose, pure, setDisplayName } from 'recompose';
import { IBot } from '../utils/AxiosFunctions';
import * as _ from 'lodash';
import * as renderIf from 'render-if';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import { ModalEnum } from '../utils/ModalEnum';
import WhiteButton from '../Assets/Buttons/WhiteButton';

const styles: CSSProperties = {
  packagesHeader: {
    display: 'flex',
    marginTop: '50px',
  },
  headerCenter: {
    flex: '1',
  },
  packagesTitle: {
    color: '#54698D',
    fontSize: '12px',
    textAlign: 'left',
    marginTop: '10px',
  },
  button: {
    marginLeft: '10px',
  },
  packages: {
    marginBottom: '100px',
  },
};

interface IPublicProps {
  bot: IBot;
}

interface IPrivateProps extends IPublicProps {}

interface IState {}

class PackageList extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
  }

  openModal = () => {
    ModalActionDispatchers.showModal(ModalEnum.createPackage);
  };

  openAddPackagesModal = () => {
    ModalActionDispatchers.showAddPackagesModal(this.props.bot);
  };

  render() {
    return (
      <div style={styles.packages}>
        <div style={styles.packagesHeader}>
          <div style={styles.packagesTitle}>{'PACKAGES'}</div>
          <div style={styles.headerCenter} />
          <WhiteButton
            text={'Create package'}
            onClick={() => {
              this.openModal();
            }}
            disabled={this.props.bot.version !== this.props.bot.currentVersion}
          />
          <WhiteButton
            text={'Add package'}
            onClick={this.openAddPackagesModal}
            disabled={this.props.bot.version !== this.props.bot.currentVersion}
            customStyles={styles.button}
          />
        </div>
        {renderIf(_.isEmpty(this.props.bot.packages))(() => (
          <p>{`There are no packages yet`}</p>
        ))}
        {renderIf(!_.isEmpty(this.props.bot.packages))(() => (
          <div>
            {this.props.bot.packages.map(pack => (
              <PackageContainer key={pack} packageResource={pack} />
            ))}
          </div>
        ))}
      </div>
    );
  }
}

const ComposedPackageList: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('PackageList'),
)(PackageList);

export default ComposedPackageList;
